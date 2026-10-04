# Push notifications – setup guide (what YOU must do)

The app code is ready. It needs two different things from Firebase. **Neither is committed to GitHub.**

| File | Purpose | Where it goes |
|---|---|---|
| `google-services.json` | identifies the *app* to Firebase (client config) | GitHub **Secret** `GOOGLE_SERVICES_JSON` (or `app/google-services.json` locally – git-ignored) |
| service-account `.json` | lets *WordPress* send messages (**private key!**) | on your web server, **outside** `public_html`; never in the app, never in GitHub |

## Part A – Firebase project + the app (10 min)
1. https://console.firebase.google.com → **Add project** (e.g. "BRAVE Portal"). Analytics can be off.
2. Project overview → **Add app → Android**. Package name must be exactly **`com.brave.portal`** (same as `APP_ID` in `brave.properties`). Register app.
3. **Download `google-services.json`**. (Skip Firebase's "add SDK" steps – already done.)
4. GitHub repo → **Settings → Secrets and variables → Actions → New repository secret**
   Name: `GOOGLE_SERVICES_JSON`, Value: open the file and paste its **entire content**.
5. Run the workflow (**Actions → Android Build → Run workflow**). The new APK now contains push support.
   (Local Android Studio builds: copy the file to `app/google-services.json`.)

## Part B – let WordPress send (15 min)
1. Firebase → ⚙ **Project settings → Service accounts → Generate new private key**. A `.json` downloads. **Treat it like a password.**
2. Upload it via SFTP/cPanel File Manager to a folder **above** the web root, e.g. `/home/USER/private/brave-firebase-sa.json` (not inside `public_html`). Permissions `600`.
3. Copy `wordpress/brave-fcm-notifier/` to `wp-content/plugins/` and activate **BRAVE FCM Notifier**.
4. Add to `wp-config.php` (above "That's all, stop editing"):
```php
define( 'BRAVE_FCM_PROJECT_ID', 'your-firebase-project-id' );   // Firebase → Project settings → Project ID
define( 'BRAVE_FCM_SERVICE_ACCOUNT_FILE', '/home/USER/private/brave-firebase-sa.json' );
define( 'BRAVE_FCM_POST_TYPES', array( 'YOUR_CLASS_POST_TYPE' ) ); // see below
```
5. WordPress admin → **Tools → BRAVE Push → Send test notification**. Your phone (app installed, notifications allowed) should buzz.

### What I need to know about your site (I did not guess these)
* The **post type slug of a "class"** (WordPress → Classes → look at the URL: `edit.php?post_type=THIS`). Same for courses/resources.
  The plugin's default is `post`. Different types can have different texts via the `brave_fcm_message` filter, e.g.:
```php
add_filter( 'brave_fcm_message', function ( $m, $post ) {
    if ( 'brave_course' === $post->post_type ) {
        $m['title'] = 'New Course Available';
        $m['body']  = $post->post_title;
    }
    return $m;
}, 10, 2 );
```
* **Live-class reminders / "class starting soon" / assignment reminders** need a stored start date/time (e.g. an ACF/JetEngine date field). Tell me the field name and I'll add a scheduled reminder (WP-Cron) – I won't invent it.
* **Announcements**: either a post type for announcements, or call `Brave_FCM_Notifier::send( [ 'title'=>…, 'body'=>…, 'url'=>…, 'channel'=>'general' ] )` from your own code.

## How it works / security notes
* Message = **FCM data message** `{title, body, url, channel}` sent to topic `brave-members` (`FCM_TOPIC` in `brave.properties`, `BRAVE_FCM_TOPIC` in wp-config – keep equal).
* Tapping opens the app and loads `url` – **only** if it is an `https` URL on the BRAVE host. Anything else is ignored.
* Topic messages go to **every install**, not only logged-in members: keep texts non-sensitive (titles/links only). Pages still require login on the website.
* Android 13+ asks for notification permission once, after the first page loads.
* Channels: *New classes & resources*, *Live class reminders*, *Announcements & updates* (`channel` = `classes`, `reminders`, `general`).
* Temporary network errors never create notifications.
