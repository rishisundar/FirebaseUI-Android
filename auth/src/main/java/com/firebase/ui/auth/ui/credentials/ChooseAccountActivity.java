/*
 * Copyright 2016 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.firebase.ui.auth.ui.credentials;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.util.Log;

import com.firebase.ui.auth.BuildConfig;
import com.firebase.ui.auth.api.CredentialsAPI;
import com.firebase.ui.auth.choreographer.ControllerConstants;
import com.firebase.ui.auth.choreographer.idp.provider.IDPProviderParcel;
import com.firebase.ui.auth.ui.NoControllerBaseActivity;
import com.firebase.ui.auth.ui.idp.AuthMethodPickerActivity;
import com.firebase.ui.auth.ui.idp.IDPSignInContainerActivity;
import com.google.android.gms.auth.api.credentials.Credential;
import com.google.android.gms.auth.api.credentials.IdentityProviders;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FacebookAuthProvider;
import com.google.firebase.auth.GoogleAuthProvider;

import java.util.ArrayList;

public class ChooseAccountActivity extends NoControllerBaseActivity {
    private static final String TAG = "ChooseAccountActivity";
    private static final int RC_CREDENTIALS_READ = 2;

    protected CredentialsAPI mCredentialsApi;

    @Override
    protected void onCreate(Bundle savedInstance) {
        super.onCreate(savedInstance);
        String apiaryKey = getIntent().getStringExtra(ControllerConstants.EXTRA_APIARY_KEY);
        String applicationId = getIntent().getStringExtra(ControllerConstants.EXTRA_APPLICATION_ID);

        // initialize the FirebaseApp
        getFirebaseApp(mAppName, apiaryKey, applicationId);

        mCredentialsApi = new CredentialsAPI(this, new CredentialsAPI.CallbackInterface() {
            @Override
            public void onAsyncTaskFinished() {
                onCredentialsApiConnected();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mCredentialsApi.isGoogleApiClient()){
            mCredentialsApi.getGoogleApiClient().connect();
        }
    }

    @Override
    protected void onStop() {
        if (mCredentialsApi.isGoogleApiClient()
                && mCredentialsApi.getGoogleApiClient().isConnected()) {
            mCredentialsApi.getGoogleApiClient().disconnect();
        }
        super.onStop();
    }



    public static Intent createIntent(
            Context context,
            String appName,
            String apiaryKey,
            String applicationId,
            ArrayList<IDPProviderParcel> providerParcels,
            String tosUrl,
            int theme
    ) {
        return new Intent()
                .setClass(context, ChooseAccountActivity.class)
                .putExtra(ControllerConstants.EXTRA_APP_NAME, appName)
                .putExtra(ControllerConstants.EXTRA_APIARY_KEY, apiaryKey)
                .putExtra(ControllerConstants.EXTRA_APPLICATION_ID, applicationId)
                .putParcelableArrayListExtra(ControllerConstants.EXTRA_PROVIDERS, providerParcels)
                .putExtra(ControllerConstants.EXTRA_TERMS_OF_SERVICE_URL, tosUrl)
                .putExtra(ControllerConstants.EXTRA_THEME, theme);
    }

    public void onCredentialsApiConnected() {
        // called back when the CredentialsAPI connects
        String email = mCredentialsApi.getEmailFromCredential();
        String password = mCredentialsApi.getPasswordFromCredential();
        String accountType = mCredentialsApi.getAccountTypeFromCredential();
        if (mCredentialsApi.isPlayServicesAvailable()
                && mCredentialsApi.isCredentialsAvailable()) {
            if (mCredentialsApi.isAutoSignInAvailable()) {
                mCredentialsApi.googleSilentSignIn();
                // TODO: (serikb) authenticate Firebase user and continue to application
                if (password != null && !password.isEmpty()) {
                    // login with username/password
                    getFirebaseAuth().signInWithEmailAndPassword(email, password)
                            .addOnCompleteListener(new OnCompleteListener<AuthResult>() {
                                @Override
                                public void onComplete(@NonNull Task<AuthResult> task) {
                                    finish(Activity.RESULT_OK, new Intent());
                                }
                            });
                } else {
                    // log in with id/provider
                    redirectToIdpSignIn(email, accountType, mProviderParcels);
                }
            } else if (mCredentialsApi.isSignInResolutionNeeded()) {
                // resolve credential
                mCredentialsApi.resolveSavedEmails(this);
            } else {
                startActivity(AuthMethodPickerActivity.createIntent(
                        getApplicationContext(),
                        mAppName,
                        mProviderParcels
                ));
                finish();
            }
        } else {
            startActivity(AuthMethodPickerActivity.createIntent(
                    getApplicationContext(),
                    mAppName,
                    mProviderParcels
            ));
            finish();
        }
    }

    private void logInWithCredential(final String email, final String password, final String accountType) {
        if (email != null
                && mCredentialsApi.isCredentialsAvailable()
                && !mCredentialsApi.isSignInResolutionNeeded()) {
            if (password != null && !password.isEmpty()) {
                // email/password combination
                getFirebaseAuth().signInWithEmailAndPassword(email, password)
                        .addOnCompleteListener(new OnCompleteListener<AuthResult>() {
                            @Override
                            public void onComplete(@NonNull Task<AuthResult> task) {
                                finish(RESULT_OK, new Intent());
                            }
                        })
                        .addOnFailureListener(new OnFailureListener() {
                            @Override
                            public void onFailure(@NonNull Exception ex) {
                                // email/password auth failed, go to the AuthMethodPickerActivity
                                startActivity(AuthMethodPickerActivity.createIntent(
                                        getApplicationContext(),
                                        mAppName,
                                        mProviderParcels
                                ));
                                finish(RESULT_OK, new Intent());
                            }
                        });
            } else {
                // identifier/provider combination
                redirectToIdpSignIn(email, accountType, mProviderParcels);
                finish(RESULT_OK, new Intent());
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "onActivityResult:" + requestCode + ":" + resultCode + ":" + data);
        }

        if (requestCode == RC_CREDENTIALS_READ) {
            if (resultCode == RESULT_OK) {
                // credential selected from SmartLock, log in with that credential
                Credential credential = data.getParcelableExtra(Credential.EXTRA_KEY);
                mCredentialsApi.handleCredential(credential);
                mCredentialsApi.resolveSignIn();
                logInWithCredential(
                        mCredentialsApi.getEmailFromCredential(),
                        mCredentialsApi.getPasswordFromCredential(),
                        mCredentialsApi.getAccountTypeFromCredential()
                );
            } else if (resultCode == RESULT_CANCELED) {
                // Smart lock selector cancelled, go to the AuthMethodPicker screen
                startActivity(AuthMethodPickerActivity.createIntent(
                        getApplicationContext(),
                        mAppName,
                        mProviderParcels
                ));
                finish(RESULT_OK, new Intent());
            } else if (resultCode == RESULT_FIRST_USER) {
                // TODO: (serikb) figure out flow
            }
        }
    }

    protected void redirectToIdpSignIn(
            String email, String accountType, ArrayList<IDPProviderParcel> providers) {
        Intent nextIntent;
        switch (accountType) {
            case IdentityProviders.GOOGLE:
                nextIntent = IDPSignInContainerActivity.createIntent(
                        this,
                        GoogleAuthProvider.PROVIDER_ID,
                        email,
                        providers,
                        mAppName);
                break;
            case IdentityProviders.FACEBOOK:
                nextIntent =
                        IDPSignInContainerActivity.createIntent(
                                this,
                                FacebookAuthProvider.PROVIDER_ID,
                                email,
                                providers,
                                mAppName);
                break;
            default:
                Log.w(TAG, "unknown provider: " + accountType);
                nextIntent = AuthMethodPickerActivity.createIntent(
                        this,
                        mAppName,
                        providers
                );
        }
        this.startActivity(nextIntent);
        finish();
    }
}