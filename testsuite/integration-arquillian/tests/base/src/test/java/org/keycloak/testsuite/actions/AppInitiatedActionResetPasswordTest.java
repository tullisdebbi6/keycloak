/*
 * Copyright 2019 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.keycloak.testsuite.actions;

import jakarta.mail.internet.MimeMessage;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.jboss.arquillian.drone.api.annotation.Drone;
import org.jboss.arquillian.graphene.page.Page;
import org.junit.After;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.cookie.CookieType;
import org.keycloak.events.Details;
import org.keycloak.events.EventType;
import org.keycloak.events.email.EmailEventListenerProviderFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.credential.PasswordCredentialModel;
import org.keycloak.representations.idm.EventRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.representations.idm.UserSessionRepresentation;
import org.keycloak.services.managers.AuthenticationSessionManager;
import org.keycloak.testsuite.admin.ApiUtil;
import org.keycloak.testsuite.pages.LoginPasswordUpdatePage;
import org.keycloak.testsuite.updaters.RealmAttributeUpdater;
import org.keycloak.testsuite.updaters.UserAttributeUpdater;
import org.keycloak.testsuite.util.GreenMailRule;
import org.keycloak.testsuite.util.MailUtils;
import org.keycloak.testsuite.util.OAuthClient;
import org.keycloak.testsuite.util.SecondBrowser;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.WebDriver;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * @author Stan Silvert
 */
public class AppInitiatedActionResetPasswordTest extends AbstractAppInitiatedActionTest {

    @Override
    protected String getAiaAction() {
        return UserModel.RequiredAction.UPDATE_PASSWORD.name();
    }

    @Override
    public void configureTestRealm(RealmRepresentation testRealm) {
        testRealm.setResetPasswordAllowed(Boolean.TRUE);
    }

    @Rule
    public GreenMailRule greenMail = new GreenMailRule();

    @Page
    protected LoginPasswordUpdatePage changePasswordPage;

    @Drone
    @SecondBrowser
    private WebDriver driver2;

    @After
    public void after() {
        ApiUtil.resetUserPassword(testRealm().users().get(findUser("test-user@localhost").getId()), "password", false);
    }

    @Test
    public void resetPassword() throws Exception {
        try (RealmAttributeUpdater realmUpdater = new RealmAttributeUpdater(testRealm())
                .addEventsListener(EmailEventListenerProviderFactory.ID)
                .update();
             UserAttributeUpdater userUpdater = new UserAttributeUpdater(ApiUtil.findUserByUsernameId(testRealm(), "test-user@localhost"))
                .setEmailVerified(true)
                .update()) {

            loginPage.open();
            loginPage.login("test-user@localhost", "password");

            events.expectLogin().assertEvent();

            doAIA();

            changePasswordPage.assertCurrent();
            assertTrue(changePasswordPage.isCancelDisplayed());

            Cookie authSessionCookie = driver.manage().getCookieNamed(CookieType.AUTH_SESSION_ID.getName());
            String authSessionId = authSessionCookie.getValue().split("\\.")[0];
            testingClient.server().run(session -> {
                // ensure that our logic to detect the authentication session works as expected
                RealmModel realm = session.realms().getRealm(TEST_REALM_NAME);
                String decodedAuthSessionId = new AuthenticationSessionManager(session).decodeBase64AndValidateSignature(authSessionId, false);
                assertNotNull(session.authenticationSessions().getRootAuthenticationSession(realm, decodedAuthSessionId));
            });

            changePasswordPage.changePassword("new-password", "new-password");

            testingClient.server().run(session -> {
                // ensure that the authentication session has been terminated
                RealmModel realm = session.realms().getRealm(TEST_REALM_NAME);
                assertNull(session.authenticationSessions().getRootAuthenticationSession(realm, authSessionId));
            });

            events.expectRequiredAction(EventType.UPDATE_PASSWORD).assertEvent();
            events.expectRequiredAction(EventType.UPDATE_CREDENTIAL).detail(Details.CREDENTIAL_TYPE, PasswordCredentialModel.TYPE).assertEvent();

            MimeMessage[] receivedMessages = greenMail.getReceivedMessages();
            Assert.assertEquals(2, receivedMessages.length);

            Assert.assertEquals("Update password", receivedMessages[0].getSubject());
            Assert.assertEquals("Update credential", receivedMessages[1].getSubject());
            MatcherAssert.assertThat(MailUtils.getBody(receivedMessages[1]).getText(),
                    Matchers.startsWith("Your password credential was changed"));
            MatcherAssert.assertThat(MailUtils.getBody(receivedMessages[1]).getHtml(),
                    Matchers.containsString("Your password credential was changed"));

            assertKcActionStatus(SUCCESS);

            EventRepresentation loginEvent = events.expectLogin().assertEvent();

            OAuthClient.AccessTokenResponse tokenResponse = sendTokenRequestAndGetResponse(loginEvent);
            oauth.idTokenHint(tokenResponse.getIdToken()).openLogout();

            events.expectLogout(loginEvent.getSessionId()).assertEvent();

            loginPage.open();
            loginPage.login("test-user@localhost", "new-password");

            events.expectLogin().assertEvent();
        }
    }

    @Test
    public void resetPasswordRequiresReAuth() throws Exception {
        loginPage.open();
        loginPage.login("test-user@localhost", "password");

        events.expectLogin().assertEvent();

        setTimeOffset(350);

        // Should prompt for re-authentication
        doAIA();

        loginPage.assertCurrent();
        Assert.assertEquals("test-user@localhost", loginPage.getAttemptedUsername());
        loginPage.login("password");

        changePasswordPage.assertCurrent();
        assertTrue(changePasswordPage.isCancelDisplayed());

        changePasswordPage.changePassword("new-password", "new-password");

        events.expectRequiredAction(EventType.UPDATE_PASSWORD).assertEvent();
        events.expectRequiredAction(EventType.UPDATE_CREDENTIAL).detail(Details.CREDENTIAL_TYPE, PasswordCredentialModel.TYPE).assertEvent();
        assertKcActionStatus(SUCCESS);
    }

    /**
     * See GH-12943
     * @throws Exception
     */
    @Test
    public void resetPasswordRequiresReAuthWithMaxAuthAgePasswordPolicy() throws Exception {

        // set password policy
        RealmRepresentation currentTestRealmRep = testRealm().toRepresentation();
        String previousPasswordPolicy = currentTestRealmRep.getPasswordPolicy();
        if (previousPasswordPolicy == null) {
            previousPasswordPolicy = "";
        }
        currentTestRealmRep.setPasswordPolicy("maxAuthAge(0)");
        try {
            testRealm().update(currentTestRealmRep);

            loginPage.open();
            loginPage.login("test-user@localhost", "password");

            events.expectLogin().assertEvent();

            // we need to add some slack to avoid timing issues
            setTimeOffset(1);

            // Should prompt for re-authentication due to maxAuthAge password policy
            doAIA();

            loginPage.assertCurrent();

            Assert.assertEquals("test-user@localhost", loginPage.getAttemptedUsername());

            loginPage.login("password");

            changePasswordPage.assertCurrent();
            assertTrue(changePasswordPage.isCancelDisplayed());

            changePasswordPage.changePassword("new-password", "new-password");

            events.expectRequiredAction(EventType.UPDATE_PASSWORD).assertEvent();
            events.expectRequiredAction(EventType.UPDATE_CREDENTIAL).detail(Details.CREDENTIAL_TYPE, PasswordCredentialModel.TYPE).assertEvent();
            assertKcActionStatus(SUCCESS);
        } finally {
            // reset password policy to previous state
            currentTestRealmRep.setPasswordPolicy(previousPasswordPolicy);
            testRealm().update(currentTestRealmRep);
        }
    }

    @Test
    public void cancelChangePassword() throws Exception {
        doAIA();

        loginPage.login("test-user@localhost", "password");

        changePasswordPage.assertCurrent();
        changePasswordPage.cancel();

        assertKcActionStatus(CANCELLED);
    }

    @Test
    public void resetPasswordUserHasUpdatePasswordRequiredAction() throws Exception {
        loginPage.open();
        loginPage.login("test-user@localhost", "password");

        UserResource userResource = testRealm().users().get(findUser("test-user@localhost").getId());
        UserRepresentation userRep = userResource.toRepresentation();
        userRep.getRequiredActions().add(UserModel.RequiredAction.UPDATE_PASSWORD.name());
        userResource.update(userRep);

        events.expectLogin().assertEvent();

        doAIA();

        changePasswordPage.assertCurrent();
        /*
         * cancel should not be supported, because the action is not only application-initiated, but also required by
         * Keycloak
         */
        assertFalse(changePasswordPage.isCancelDisplayed());

        changePasswordPage.changePassword("new-password", "new-password");

        events.expectRequiredAction(EventType.UPDATE_PASSWORD).assertEvent();
        events.expectRequiredAction(EventType.UPDATE_CREDENTIAL).detail(Details.CREDENTIAL_TYPE, PasswordCredentialModel.TYPE).assertEvent();

        assertKcActionStatus(SUCCESS);
    }

    @Test
    public void checkLogoutSessions() {
        OAuthClient oauth2 = new OAuthClient();
        oauth2.init(driver2);

        loginPage.open();
        loginPage.login("test-user@localhost", "password");
        events.expectLogin().assertEvent();

        UserResource testUser = testRealm().users().get(findUser("test-user@localhost").getId());
        List<UserSessionRepresentation> sessions = testUser.getUserSessions();
        assertEquals(1, sessions.size());
        final String firstSessionId = sessions.get(0).getId();

        oauth2.doLogin("test-user@localhost", "password");
        EventRepresentation event2 = events.expectLogin().assertEvent();
        assertEquals(2, testUser.getUserSessions().size());

        doAIA();

        changePasswordPage.assertCurrent();
        assertTrue("Logout sessions is checked by default", changePasswordPage.isLogoutSessionsChecked());
        changePasswordPage.changePassword("All Right Then, Keep Your Secrets", "All Right Then, Keep Your Secrets");
        events.expectLogout(event2.getSessionId()).detail(Details.LOGOUT_TRIGGERED_BY_REQUIRED_ACTION, UserModel.RequiredAction.UPDATE_PASSWORD.name()).assertEvent();
        events.expectRequiredAction(EventType.UPDATE_PASSWORD).assertEvent();
        events.expectRequiredAction(EventType.UPDATE_CREDENTIAL).detail(Details.CREDENTIAL_TYPE, PasswordCredentialModel.TYPE).assertEvent();
        assertKcActionStatus(SUCCESS);

        sessions = testUser.getUserSessions();
        assertEquals(1, sessions.size());
        assertEquals("Old session is still valid", firstSessionId, sessions.get(0).getId());
    }

    @Test
    public void uncheckLogoutSessions() {
        OAuthClient oauth2 = new OAuthClient();
        oauth2.init(driver2);

        UserResource testUser = testRealm().users().get(findUser("test-user@localhost").getId());

        loginPage.open();
        loginPage.login("test-user@localhost", "password");
        events.expectLogin().assertEvent();

        oauth2.doLogin("test-user@localhost", "password");
        events.expectLogin().assertEvent();
        assertEquals(2, testUser.getUserSessions().size());

        doAIA();

        changePasswordPage.assertCurrent();
        changePasswordPage.uncheckLogoutSessions();
        changePasswordPage.changePassword("All Right Then, Keep Your Secrets", "All Right Then, Keep Your Secrets");
        events.expectRequiredAction(EventType.UPDATE_PASSWORD).assertEvent();
        events.expectRequiredAction(EventType.UPDATE_CREDENTIAL).detail(Details.CREDENTIAL_TYPE, PasswordCredentialModel.TYPE).assertEvent();
        assertKcActionStatus(SUCCESS);

        assertEquals(2, testUser.getUserSessions().size());
    }

}
