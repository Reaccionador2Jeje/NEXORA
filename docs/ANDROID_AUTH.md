# NEXORA Android authentication setup

NEXORA uses Supabase Auth with browser-based OAuth and PKCE. The Android callback is:

`dev.atdev.nexora://auth/callback`

Supabase project: `opxhywwivpzrzpswbotx`

## 1. Configure the Android redirect in Supabase

Open **Authentication → URL Configuration** in the Supabase dashboard and add this exact Redirect URL:

`dev.atdev.nexora://auth/callback`

Do not use the provider callback URL in this field.

## 2. Configure Google

Create a Google OAuth client of type **Web application** in Google Cloud Console.

Add this exact URI under **Authorized redirect URIs**:

`https://opxhywwivpzrzpswbotx.supabase.co/auth/v1/callback`

In Supabase, open **Authentication → Sign In / Providers → Google**, enable Google, and enter the Web client ID and client secret.

## 3. Configure GitHub

Create a GitHub OAuth App in GitHub Developer Settings.

- Homepage URL: `https://github.com/Reaccionador2Jeje/NEXORA`
- Authorization callback URL: `https://opxhywwivpzrzpswbotx.supabase.co/auth/v1/callback`

In Supabase, open **Authentication → Sign In / Providers → GitHub**, enable GitHub, and enter the OAuth App client ID and client secret.

## 4. Security notes

- Provider client secrets belong only in the Supabase dashboard. Never commit them or place them in the APK.
- The Android app contains only the Supabase publishable key, which is designed for client applications.
- OAuth uses PKCE; the temporary verifier and session tokens are encrypted with Android Keystore-backed AES-GCM storage.
- After enabling both providers and saving the redirect URL, install a fresh NEXORA build and test Google and GitHub sign-in from the side menu.
