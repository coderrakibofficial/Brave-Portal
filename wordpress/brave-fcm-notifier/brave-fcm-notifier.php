<?php
/**
 * Plugin Name: BRAVE FCM Notifier
 * Description: Sends a Firebase Cloud Messaging push notification to the BRAVE Portal app when selected content is published.
 * Version:     1.0.0
 * Author:      Eavico
 *
 * SECURITY: the Firebase service-account key is NEVER stored in the database, in this plugin or in git.
 * It lives in a JSON file outside the public web root, referenced from wp-config.php (see FIREBASE_SETUP.md).
 */

if ( ! defined( 'ABSPATH' ) ) {
	exit;
}

/*
 * Required in wp-config.php:
 *   define( 'BRAVE_FCM_PROJECT_ID', 'your-firebase-project-id' );
 *   define( 'BRAVE_FCM_SERVICE_ACCOUNT_FILE', '/home/USER/private/brave-firebase-service-account.json' );
 * Optional in wp-config.php:
 *   define( 'BRAVE_FCM_TOPIC', 'brave-members' );              // must equal FCM_TOPIC in brave.properties
 *   define( 'BRAVE_FCM_POST_TYPES', array( 'brave_class' ) );  // post types that trigger a notification
 */

final class Brave_FCM_Notifier {

	const META_SENT = '_brave_fcm_sent';
	const CRON_HOOK = 'brave_fcm_send_for_post';

	public static function init() {
		add_action( 'transition_post_status', array( __CLASS__, 'on_status_change' ), 10, 3 );
		add_action( self::CRON_HOOK, array( __CLASS__, 'send_for_post' ), 10, 1 );
		add_action( 'admin_menu', array( __CLASS__, 'admin_menu' ) );
	}

	/** Post types that trigger notifications. Set BRAVE_FCM_POST_TYPES to your real class post type. */
	private static function post_types() {
		$types = defined( 'BRAVE_FCM_POST_TYPES' ) ? (array) BRAVE_FCM_POST_TYPES : array( 'post' );
		return apply_filters( 'brave_fcm_post_types', $types );
	}

	private static function topic() {
		return defined( 'BRAVE_FCM_TOPIC' ) ? BRAVE_FCM_TOPIC : 'brave-members';
	}

	/** Fires when a post becomes published for the first time. */
	public static function on_status_change( $new_status, $old_status, $post ) {
		if ( 'publish' !== $new_status || 'publish' === $old_status ) {
			return;
		}
		if ( wp_is_post_revision( $post ) || wp_is_post_autosave( $post ) ) {
			return;
		}
		if ( ! in_array( $post->post_type, self::post_types(), true ) ) {
			return;
		}
		if ( get_post_meta( $post->ID, self::META_SENT, true ) ) {
			return; // never notify twice for the same item
		}
		if ( ! apply_filters( 'brave_fcm_should_send', true, $post ) ) {
			return;
		}
		// Send in the background so publishing is never slowed down or blocked by a network problem.
		wp_schedule_single_event( time() + 5, self::CRON_HOOK, array( $post->ID ) );
	}

	public static function send_for_post( $post_id ) {
		$post = get_post( $post_id );
		if ( ! $post || 'publish' !== $post->post_status || get_post_meta( $post_id, self::META_SENT, true ) ) {
			return;
		}
		$message = apply_filters(
			'brave_fcm_message',
			array(
				'title'   => 'New Class Available',
				'body'    => 'A new BRAVE class has just been uploaded. Tap to watch now.',
				'channel' => 'classes', // classes | reminders | general
			),
			$post
		);
		$message['url'] = get_permalink( $post );

		$result = self::send( $message );
		if ( true === $result ) {
			update_post_meta( $post_id, self::META_SENT, time() );
		} else {
			error_log( '[BRAVE FCM] send failed for post ' . $post_id . ': ' . $result );
		}
	}

	/** Sends one message to the members topic. Returns true or an error string. */
	public static function send( array $message ) {
		if ( ! defined( 'BRAVE_FCM_PROJECT_ID' ) ) {
			return 'BRAVE_FCM_PROJECT_ID is not defined in wp-config.php';
		}
		$token = self::access_token();
		if ( ! is_string( $token ) || '' === $token ) {
			return is_string( $token ) ? 'empty access token' : 'could not get access token';
		}

		// FCM data values must all be strings. Data-only message => the app builds the notification itself.
		$data = array();
		foreach ( array( 'title', 'body', 'url', 'channel' ) as $key ) {
			$data[ $key ] = isset( $message[ $key ] ) ? (string) $message[ $key ] : '';
		}
		$payload = array(
			'message' => array(
				'topic'   => self::topic(),
				'data'    => $data,
				'android' => array( 'priority' => 'HIGH' ),
			),
		);

		$response = wp_remote_post(
			'https://fcm.googleapis.com/v1/projects/' . rawurlencode( BRAVE_FCM_PROJECT_ID ) . '/messages:send',
			array(
				'timeout' => 15,
				'headers' => array(
					'Authorization' => 'Bearer ' . $token,
					'Content-Type'  => 'application/json; charset=utf-8',
				),
				'body'    => wp_json_encode( $payload ),
			)
		);
		if ( is_wp_error( $response ) ) {
			return $response->get_error_message();
		}
		$code = wp_remote_retrieve_response_code( $response );
		if ( $code < 200 || $code >= 300 ) {
			return 'HTTP ' . $code . ' ' . wp_remote_retrieve_body( $response );
		}
		return true;
	}

	/** OAuth2 access token from the service account (JWT bearer flow), cached for ~55 minutes. */
	private static function access_token() {
		$cached = get_transient( 'brave_fcm_access_token' );
		if ( $cached ) {
			return $cached;
		}
		if ( ! defined( 'BRAVE_FCM_SERVICE_ACCOUNT_FILE' ) || ! is_readable( BRAVE_FCM_SERVICE_ACCOUNT_FILE ) ) {
			return false;
		}
		$sa = json_decode( file_get_contents( BRAVE_FCM_SERVICE_ACCOUNT_FILE ), true ); // phpcs:ignore
		if ( empty( $sa['client_email'] ) || empty( $sa['private_key'] ) ) {
			return false;
		}

		$b64 = function ( $s ) {
			return rtrim( strtr( base64_encode( $s ), '+/', '-_' ), '=' );
		};
		$now    = time();
		$header = $b64( wp_json_encode( array( 'alg' => 'RS256', 'typ' => 'JWT' ) ) );
		$claims = $b64(
			wp_json_encode(
				array(
					'iss'   => $sa['client_email'],
					'scope' => 'https://www.googleapis.com/auth/firebase.messaging',
					'aud'   => 'https://oauth2.googleapis.com/token',
					'iat'   => $now,
					'exp'   => $now + 3600,
				)
			)
		);
		$signature = '';
		if ( ! openssl_sign( $header . '.' . $claims, $signature, $sa['private_key'], OPENSSL_ALGO_SHA256 ) ) {
			return false;
		}
		$jwt = $header . '.' . $claims . '.' . $b64( $signature );

		$response = wp_remote_post(
			'https://oauth2.googleapis.com/token',
			array(
				'timeout' => 15,
				'body'    => array(
					'grant_type' => 'urn:ietf:params:oauth:grant-type:jwt-bearer',
					'assertion'  => $jwt,
				),
			)
		);
		if ( is_wp_error( $response ) || 200 !== wp_remote_retrieve_response_code( $response ) ) {
			return false;
		}
		$body = json_decode( wp_remote_retrieve_body( $response ), true );
		if ( empty( $body['access_token'] ) ) {
			return false;
		}
		set_transient( 'brave_fcm_access_token', $body['access_token'], 55 * MINUTE_IN_SECONDS );
		return $body['access_token'];
	}

	// ---- Admin page: Tools -> BRAVE Push (send a test notification) ----
	public static function admin_menu() {
		add_management_page( 'BRAVE Push', 'BRAVE Push', 'manage_options', 'brave-push', array( __CLASS__, 'admin_page' ) );
	}

	public static function admin_page() {
		if ( ! current_user_can( 'manage_options' ) ) {
			return;
		}
		$notice = '';
		if ( isset( $_POST['brave_fcm_test'] ) && check_admin_referer( 'brave_fcm_test' ) ) {
			$result = self::send(
				array(
					'title'   => 'BRAVE test notification',
					'body'    => 'If you can read this, push notifications are working.',
					'url'     => home_url( '/' ),
					'channel' => 'general',
				)
			);
			$notice = true === $result
				? '<div class="notice notice-success"><p>Test notification sent.</p></div>'
				: '<div class="notice notice-error"><p>Failed: ' . esc_html( $result ) . '</p></div>';
		}
		echo '<div class="wrap"><h1>BRAVE Push</h1>' . $notice; // phpcs:ignore
		echo '<p>Topic: <code>' . esc_html( self::topic() ) . '</code> &middot; Post types: <code>' . esc_html( implode( ', ', self::post_types() ) ) . '</code></p>';
		echo '<form method="post">';
		wp_nonce_field( 'brave_fcm_test' );
		echo '<p><button class="button button-primary" name="brave_fcm_test" value="1">Send test notification</button></p></form></div>';
	}
}

Brave_FCM_Notifier::init();
