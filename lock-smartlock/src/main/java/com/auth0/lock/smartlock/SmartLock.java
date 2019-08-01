package com.auth0.lock.smartlock;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.auth0.api.APIClient;
import com.auth0.api.callback.AuthenticationCallback;
import com.auth0.core.Token;
import com.auth0.core.UserProfile;
import com.auth0.lock.Lock;
import com.auth0.lock.LockProvider;
import com.auth0.lock.credentials.CredentialStore;
import com.auth0.lock.credentials.CredentialStoreCallback;
import com.auth0.lock.error.ErrorDialogBuilder;
import com.auth0.lock.error.LoginAuthenticationErrorBuilder;
import com.auth0.lock.event.AuthenticationError;
import com.google.android.gms.auth.api.Auth;
import com.google.android.gms.auth.api.credentials.Credential;
import com.google.android.gms.auth.api.credentials.CredentialRequest;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;

import java.lang.ref.WeakReference;

/**
 * Main class of Auth0 Lock integration with SmartLock for Android
 * This class is a {@link CredentialStore} itself so it can save user's credentials.
 * To start just instantiate it using {@link com.auth0.lock.smartlock.SmartLock} like this inside your {@link Application} object:
 * <pre>
 *     <code>
 *      lock = new SmartLock.Builder(this)
 *              .loadFromApplication(this)
 *              .closable(true)
 *              .build();
 *     </code>
 * </pre>
 *
 * Then just invoke the login activity:
 * <pre>
 *     <code>
 *      SmartLock.getLock(activity).loginFromActivity(activity);
 *     </code>
 * </pre>
 */
public class SmartLock extends Lock implements CredentialStore, GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

    public static final int SMART_LOCK_READ = 90001;
    public static final int SMART_LOCK_SAVE = 90002;

    private static final String TAG = SmartLock.class.getName();

    final GoogleApiClient credentialClient;
    CredentialRequest credentialRequest;

    private CredentialStoreCallback callback;
    private WeakReference<GoogleApiClientConnectTask> task;

    public SmartLock(Context context, APIClient apiClient) {
        super(apiClient);
        this.credentialClient = new GoogleApiClient.Builder(context.getApplicationContext())
                                    .addConnectionCallbacks(this)
                                    .addOnConnectionFailedListener(this)
                                    .addApi(Auth.CREDENTIALS_API)
                                    .build();
        clearTask();
        clearCredentialStoreCallback();
    }

    SmartLock(GoogleApiClient credentialClient, APIClient client) {
        super(client);
        this.credentialClient = credentialClient;
        clearTask();
        clearCredentialStoreCallback();
    }

    /**
     * Prepares SmartLock resources to perform authentication operations
     * This should be called from {@link Activity#onStart()} of the Activity that needs authentication
     */
    public void onStart() {
        getCredentialClient().connect();
    }

    /**
     * Cleans up SmartLock resources and state
     * This should be called from {@link Activity#onStop()} of the Activity that needs authentication
     */
    public void onStop() {
        clearCredentialStoreCallback();
        credentialRequest = null;
        getCredentialClient().disconnect();
        GoogleApiClientConnectTask task = currentTask();
        if (task != null) {
            task.cancel(false);
        }
        clearTask();
    }

    /**
     * Method called in {@link Activity#onActivityResult(int, int, Intent)} callback to handle SmartLock interaction
     * @param activity that received the result
     * @param requestCode of the request
     * @param resultCode received by the activity
     * @param data of the result
     */
    @Override
    public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case SMART_LOCK_READ:
                if (resultCode == Activity.RESULT_OK) {
                    Credential credential = data.getParcelableExtra(Credential.EXTRA_KEY);
                    onCredentialsRetrieved(activity, credential);
                } else {
                    startLockFromActivity(activity);
                }
                break;
            case SMART_LOCK_SAVE:
                final CredentialStoreCallback callback = getCallback();
                if (resultCode == Activity.RESULT_OK) {
                    callback.onSuccess();
                } else {
                    callback.onError(CredentialStoreCallback.CREDENTIAL_STORE_SAVE_CANCELLED, null);
                }
                clearCredentialStoreCallback();
                break;
        }
    }

    /**
     * Saves the user's credentials in Smart Lock for Android
     * @param activity that wants to save the credentials
     * @param username of the used to login
     * @param email of the user
     * @param password of the used to login
     * @param pictureUrl of the user
     * @param callback that will be called when the operation is completed
     */
    @Override
    public void saveFromActivity(Activity activity, String username, String email, String password, String pictureUrl, CredentialStoreCallback callback) {
        if ((username == null && email == null) || password == null) {
            Log.w(TAG, "Invalid credentials to save in Smart Lock");
            callback.onError(CredentialStoreCallback.CREDENTIAL_STORE_SAVE_FAILED, null);
            clearCredentialStoreCallback();
            return;
        }
        setCallback(callback);
        String id = email != null ? email : username;
        Uri pictureUri = pictureUrl != null ? Uri.parse(pictureUrl) : null;
        final Credential credential = new Credential.Builder(id)
                .setName(username)
                .setProfilePictureUri(pictureUri)
                .setPassword(password)
                .build();
        startTask(new SaveCredentialTask(activity, credential));
    }

    /**
     * First queries SmartLock for user credentials, if none is found it will show {@link com.auth0.lock.LockActivity}.
     * Otherwise it will login with those credentials
     * @param activity that wants to start the login operation
     */
    @Override
    public void loginFromActivity(Activity activity) {
        startTask(new RequestCredentialsTask(activity));
    }

    /**
     * Disable auto-signin for Smart Lock
     */
    public void disableAutoSignIn() {
        Auth.CredentialsApi.disableAutoSignIn(this.credentialClient).setResultCallback(new ResultCallback<Status>() {
            @Override
            public void onResult(Status status) {
                Log.v(TAG, "Disabled auto sign in with status " + status.getStatusMessage());
            }
        });
    }

    @Override
    public void onConnected(Bundle bundle) {
        Log.v(TAG, "GoogleApiClient connected");
    }

    @Override
    public void onConnectionSuspended(int i) {
        Log.v(TAG, "GoogleApiClient connection suspended");
    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        Log.e(TAG, "GoogleApiClient connection failed with code " + connectionResult.getErrorCode());
    }

    void clearCredentialStoreCallback() {
        callback = new CredentialStoreCallback() {
            @Override
            public void onSuccess() {
            }
            @Override
            public void onError(int errorCode, Throwable e) {
            }
        };
    }

    @NonNull CredentialStoreCallback getCallback() {
        return callback;
    }

    void setCallback(@NonNull CredentialStoreCallback callback) {
        this.callback = callback;
    }

    @NonNull GoogleApiClient getCredentialClient() {
        return credentialClient;
    }

    @NonNull CredentialRequest newCredentialRequest() {
        credentialRequest = new CredentialRequest.Builder()
                .setSupportsPasswordLogin(true)
                .build();
        return credentialRequest;
    }

    void onCredentialsRetrieved(final Activity activity, final Credential credential) {
        Log.v(TAG, "Credentials : " + credential.getName());
        String email = credential.getId();
        String password = credential.getPassword();

        getAPIClient().login(email, password, getAuthenticationParameters(), new AuthenticationCallback() {
            @Override
            public void onSuccess(UserProfile profile, Token token) {
                Intent result = new Intent(Lock.AUTHENTICATION_ACTION)
                        .putExtra(Lock.AUTHENTICATION_ACTION_PROFILE_PARAMETER, profile)
                        .putExtra(Lock.AUTHENTICATION_ACTION_TOKEN_PARAMETER, token);
                LocalBroadcastManager.getInstance(activity).sendBroadcast(result);
            }

            @Override
            public void onFailure(Throwable throwable) {
                LoginAuthenticationErrorBuilder builder = new LoginAuthenticationErrorBuilder();
                AuthenticationError error = builder.buildFrom(throwable);
                ErrorDialogBuilder.showAlertDialog(activity, error);
            }
        });
    }

    void onCredentialRetrievalError(Activity activity, Status status, int requestCode) {
        if (status.hasResolution() && status.getStatusCode() != CommonStatusCodes.SIGN_IN_REQUIRED) {
            try {
                status.startResolutionForResult(activity, requestCode);
            } catch (IntentSender.SendIntentException e) {
                Log.e(TAG, "Failed to send intent for Smart Lock resolution", e);
                startLockFromActivity(activity);
            }
        } else {
            Log.e(TAG, "Couldn't read the credentials using Smart Lock. Showing Lock...");
            startLockFromActivity(activity);
        }
    }

    GoogleApiClientConnectTask currentTask() {
        return task.get();
    }

    private void startLockFromActivity(Activity activity) {
        super.loginFromActivity(activity);
    }

    private void clearTask() {
        task = new WeakReference<>(null);
    }

    void startTask(GoogleApiClientConnectTask task) {
        task.execute(this);
        this.task = new WeakReference<>(task);
    }

    @Override
    public CredentialStore getCredentialStore() {
        return this;
    }

    /**
     * Obtain SmartLock from the LockProvider
     * @param activity that requires Smart Lock
     * @return an instance of SmartLock
     */
    public static SmartLock getSmartLock(Activity activity) {
        Application application = activity.getApplication();
        if (!(application instanceof LockProvider)) {
            throw new IllegalStateException("Android Application object must implement LockProvider interface");
        }
        LockProvider provider = (LockProvider) application;
        final Lock lock = provider.getLock();
        if (!(lock instanceof SmartLock)) {
            throw new IllegalStateException("LockProvider must return an instance of SmartLock");
        }
        return (SmartLock) lock;
    }

    /**
     * SmartLock Builder
     */
    public static class Builder extends Lock.Builder {

        private Application application;

        /**
         * Creates a new instance of Builder
         * @param application that will own the instance of SmartLock
         */
        public Builder(Application application) {
            this.application = application;
        }

        @Override
        public Lock.Builder useCredentialStore(CredentialStore store) {
            Log.w(Builder.class.getName(), "There is no need to call this method for SmartLock");
            return super.useCredentialStore(store);
        }

        @Override
        protected Lock buildLock() {
            return new SmartLock(application, buildAPIClient());
        }
    }
}
