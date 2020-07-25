package com.oasisfeng.island.shuttle

import android.annotation.SuppressLint
import android.app.*
import android.app.Activity.RESULT_FIRST_USER
import android.app.PendingIntent.FLAG_NO_CREATE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.*
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.util.Log
import android.view.*
import com.oasisfeng.island.util.Users
import com.oasisfeng.island.util.toId
import java.io.Serializable
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class PendingIntentShuttle: BroadcastReceiver() {

	override fun onReceive(context: Context, intent: Intent) {
		if (intent.getLongExtra(ActivityOptions.EXTRA_USAGE_TIME_REPORT, -1) >= 0) return   // Ignore usage report

		val payload = intent.getParcelableExtra<Parcelable>(null).also { Log.d(TAG, "Received via shuttle: $it") }
		if (payload is Invocation)
			try { handleInvocation(context, payload) }
			catch (t: Throwable) { Log.w(TAG, "Error executing " + payload.blockClass.name, t) }
		else if (payload is PendingIntent && payload.creatorPackage == context.packageName)
			save(context, payload)
		else Log.e(TAG, "Invalid payload received: $payload")
	}

	private fun handleInvocation(context: Context, invocation: Invocation) {
		val constructor = invocation.blockClass.declaredConstructors[0]
		constructor.isAccessible = true
		val closure = invocation.closure
		val argTypes = constructor.parameterTypes
		val args: Array<Any?> = arrayOfNulls(argTypes.size)
		@Suppress("UNCHECKED_CAST") val block = constructor.newInstance(* args) as Context.() -> Any?
		block.javaClass.declaredFields.forEachIndexed { index, field -> // Constructor arguments do not matter, as all fields are replaced.
			field.apply { isAccessible = true }.set(block, if (field.type == Context::class.java) context else closure[index]) }

		val result = try { block(context) }
		catch (e: Throwable) { return setResult(RESULT_FIRST_USER, null, Bundle().apply { putSerializable(null, e) }) }

		setResult(Activity.RESULT_OK, null, Bundle().apply { @Suppress("UNCHECKED_CAST") when (result) {
			null ->             putString(null, null)
			is Serializable ->  putSerializable(null, result)   // Catch most types, which are just put into the underling map.
			is Parcelable ->    putParcelable(null, result)
			is CharSequence ->  putCharSequence(null, result)   // CharSequence may not be serializable
			else -> throw UnsupportedOperationException("Return type is not yet supported: ${result.javaClass}") }})
	}

	companion object {

		fun isReady(context: Context, profile: UserHandle) = getLocker(context, profile) != null

		internal suspend fun <R> shuttle(context: Context, shuttle: PendingIntent, procedure: Context.() -> R): R {
			val javaClass = procedure.javaClass
			val constructors: Array<Constructor<*>> = javaClass.declaredConstructors
			require(constructors.isNotEmpty()) { "The method must have at least one constructor" }

			val fields: Array<Field> = javaClass.declaredFields // Automatically generated for captured variables by compiler (indeterminate order)
			val args = fields.map { it.apply { isAccessible = true }.get(procedure) }.toTypedArray()
			val constructor = constructors[0] // Extra constructor may be generated by "Instant Run" of Android Studio.
			val count = constructor.parameterTypes.size
			require(fields.size >= count) { "Parameter types mismatch: " + constructor + " / " + fields.contentDeepToString() }

			return suspendCoroutine { continuation -> try {
				shuttle.send(context, Activity.RESULT_CANCELED, Intent().putExtra(null, Invocation(javaClass, args)), { _,_, result,_, extras ->
					when (result) {
						Activity.RESULT_OK -> continuation.resume(extras?.get(null) as R)
						RESULT_FIRST_USER -> continuation.resumeWithException(extras?.get(null) as Throwable)
						else -> continuation.resumeWithException(RuntimeException("Unexpected failure shuttling $javaClass")) }}, null)
			} catch (e: RuntimeException) { continuation.resumeWithException(e) }}
		}

		fun sendToAllUnlockedProfiles(context: Context) {
			check(Users.isOwner()) { "Must be called in owner user" }
			context.getSystemService(UserManager::class.java)!!.userProfiles.dropWhile { it == Users.current() }.forEach {
				sendToProfileIfUnlocked(context, it) }
		}

		fun waitForProfileUnlockAndSend(context: Context): BroadcastReceiver {
			return object: BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) {
				val profile: UserHandle = intent.getParcelableExtra(Intent.EXTRA_USER) ?: return
				sendToProfileIfUnlocked(context, profile)
			}}.also { context.registerReceiver(it, IntentFilter(Intent.ACTION_MANAGED_PROFILE_UNLOCKED)) }
		}

		private fun sendToProfileIfUnlocked(context: Context, profile: UserHandle): Boolean {
			if (! context.getSystemService(UserManager::class.java)!!.isUserUnlocked(profile))
				return false.also { Log.i(TAG, "Skip stopped or locked user: $profile") }
			return sendToProfile(context, profile)
		}

		private fun sendToProfile(context: Context, profile: UserHandle): Boolean {
			val la = context.getSystemService(LauncherApps::class.java)!!
			la.getActivityList(context.packageName, profile).getOrNull(0)?.also {
				la.startMainActivity(it.componentName, profile, null, buildActivityOptionsWithReverseShuttle(context))
				Log.i(TAG, "Initializing shuttle to profile ${profile.toId()}...")
				return true } ?: Log.e(TAG, "No launcher activity in profile user ${profile.toId()}")
			return false
		}

		private fun buildReverseShuttle(context: Context)  // For use by other profile to shuttle back
				= PendingIntent.getBroadcast(context, Users.current().toId(),
				Intent(context, PendingIntentShuttle::class.java), FLAG_UPDATE_CURRENT)

		private fun buildActivityOptionsWithReverseShuttle(context: Context): Bundle
				= ActivityOptions.makeSceneTransitionAnimation(context as? Activity ?: DummyActivity(context), View(context), "")
				.apply { requestUsageTimeReport(buildReverseShuttle(context)) }.toBundle()

		@JvmStatic fun retrieveFromActivity(activity: Activity): PendingIntent? {
			@SuppressLint("PrivateApi") val options = Activity::class.java.getDeclaredMethod("getActivityOptions")
					.apply { isAccessible = true }.invoke(activity) as ActivityOptions?
			return options?.toBundle()?.getParcelable<PendingIntent>(KEY_USAGE_TIME_REPORT)?.also { shuttle ->
				save(activity, shuttle)
				val reverseShuttle = buildReverseShuttle(activity)
				shuttle.send(activity, 0, Intent().putExtra(null, reverseShuttle))
			}
		}

		/** Shuttle cannot be retrieved directly as its "user" is not current, so we wrap it within a local "locker" PendingIntent.*/
		private fun save(context: Context, shuttle: PendingIntent) {
			val user = shuttle.creatorUserHandle?.takeIf { it != Users.current() }
					?: return Unit.also { Log.e(TAG, "Not a shuttle: $shuttle") }
			val locker = PendingIntent.getBroadcast(context, user.toId(),
					Intent(ACTION_SHUTTLE_LOCKER).setPackage("").putExtra(null, shuttle), FLAG_UPDATE_CURRENT)
			context.getSystemService(AlarmManager::class.java)!!.set(AlarmManager.ELAPSED_REALTIME,
					SystemClock.elapsedRealtime() + 365 * 24 * 3600_000L, locker)
		}

		internal suspend fun load(context: Context, profile: UserHandle): PendingIntent? {
			require(profile != Users.current()) { "Same profile: $profile" }
			val locker = getLocker(context, profile) ?: return null
			return suspendCoroutine { continuation -> locker.send(context, 0, null, { _, intent, _, _, _ ->
				continuation.resume(intent.getParcelableExtra(null)) }, null) }
		}

		private fun getLocker(context: Context, profile: UserHandle)
				= PendingIntent.getBroadcast(context, profile.toId(), Intent(ACTION_SHUTTLE_LOCKER).setPackage(""), FLAG_NO_CREATE)

		private const val ACTION_SHUTTLE_LOCKER = "SHUTTLE_LOCKER"
		private const val KEY_USAGE_TIME_REPORT = "android:activity.usageTimeReport"
	}

	class Invocation(internal val blockClass: Class<*>, internal val closure: Array<Any?>): Parcelable {

		override fun writeToParcel(dest: Parcel, flags: Int) = dest.run { writeString(blockClass.name); writeArray(closure) }
		override fun describeContents() = 0
		constructor(parcel: Parcel, classLoader: ClassLoader)
				: this(classLoader.loadClass(parcel.readString()), parcel.readArray(classLoader)!!)

		companion object CREATOR : Parcelable.ClassLoaderCreator<Invocation> {
			override fun createFromParcel(parcel: Parcel, classLoader: ClassLoader) = Invocation(parcel, classLoader)
			override fun createFromParcel(parcel: Parcel) = Invocation(parcel, Invocation::class.java.classLoader!!)
			override fun newArray(size: Int): Array<Invocation?> = arrayOfNulls(size)
		}
	}

	class StarterService: Service() {   // TODO: PersistentService is unavailable if owner user is not managed by Island.

		override fun onCreate() {
			if (! Users.isOwner())
				return packageManager.setComponentEnabledSetting(ComponentName(this, javaClass),
						PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
			Log.i(TAG, "Initializing shuttles...")
			sendToAllUnlockedProfiles(this)
			mReceiver = waitForProfileUnlockAndSend(this)
		}

		override fun onDestroy() {
			if (! Users.isOwner()) return
			unregisterReceiver(mReceiver)
		}

		override fun onBind(intent: Intent?) = if (Users.isOwner()) Binder() else null

		private lateinit var mReceiver: BroadcastReceiver
	}

	private class DummyActivity(context: Context): Activity() {
		override fun getWindow() = mDummyWindow
		private val mDummyWindow = DummyWindow(context)
	}
	private class DummyWindow(context: Context): Window(context) {
		init { requestFeature(FEATURE_ACTIVITY_TRANSITIONS) }   // Required for ANIM_SCENE_TRANSITION. See ActivityOptions.makeSceneTransitionAnimation().
		override fun superDispatchTrackballEvent(event: MotionEvent?) = false
		override fun setNavigationBarColor(color: Int) {}
		override fun onConfigurationChanged(newConfig: Configuration?) {}
		override fun peekDecorView() = null
		override fun setFeatureDrawableUri(featureId: Int, uri: Uri?) {}
		override fun setVolumeControlStream(streamType: Int) {}
		override fun setBackgroundDrawable(drawable: Drawable?) {}
		override fun takeKeyEvents(get: Boolean) {}
		override fun getNavigationBarColor() = 0
		override fun superDispatchGenericMotionEvent(event: MotionEvent?) = false
		override fun superDispatchKeyEvent(event: KeyEvent?) = false
		override fun getLayoutInflater(): LayoutInflater = context.getSystemService(LayoutInflater::class.java)!!
		override fun performContextMenuIdentifierAction(id: Int, flags: Int) = false
		override fun setStatusBarColor(color: Int) {}
		override fun togglePanel(featureId: Int, event: KeyEvent?) {}
		override fun performPanelIdentifierAction(featureId: Int, id: Int, flags: Int) = false
		override fun closeAllPanels() {}
		override fun superDispatchKeyShortcutEvent(event: KeyEvent?) = false
		override fun superDispatchTouchEvent(event: MotionEvent?) = false
		override fun setDecorCaptionShade(decorCaptionShade: Int) {}
		override fun takeInputQueue(callback: InputQueue.Callback?) {}
		override fun setResizingCaptionDrawable(drawable: Drawable?) {}
		override fun performPanelShortcut(featureId: Int, keyCode: Int, event: KeyEvent?, flags: Int) = false
		override fun setFeatureDrawable(featureId: Int, drawable: Drawable?) {}
		override fun saveHierarchyState() = null
		override fun addContentView(view: View?, params: ViewGroup.LayoutParams?) {}
		override fun invalidatePanelMenu(featureId: Int) {}
		override fun setTitle(title: CharSequence?) {}
		override fun setChildDrawable(featureId: Int, drawable: Drawable?) {}
		override fun closePanel(featureId: Int) {}
		override fun restoreHierarchyState(savedInstanceState: Bundle?) {}
		override fun onActive() {}
		override fun getDecorView(): View { TODO("Not yet implemented") }
		override fun setTitleColor(textColor: Int) {}
		override fun setContentView(layoutResID: Int) {}
		override fun setContentView(view: View?) {}
		override fun setContentView(view: View?, params: ViewGroup.LayoutParams?) {}
		override fun getVolumeControlStream() = AudioManager.USE_DEFAULT_STREAM_TYPE
		override fun getCurrentFocus(): View? = null
		override fun getStatusBarColor() = 0
		override fun isShortcutKey(keyCode: Int, event: KeyEvent?) = false
		override fun setFeatureDrawableAlpha(featureId: Int, alpha: Int) {}
		override fun isFloating() = false
		override fun setFeatureDrawableResource(featureId: Int, resId: Int) {}
		override fun setFeatureInt(featureId: Int, value: Int) {}
		override fun setChildInt(featureId: Int, value: Int) {}
		override fun takeSurface(callback: SurfaceHolder.Callback2?) {}
		override fun openPanel(featureId: Int, event: KeyEvent?) {}
	}
}

private const val TAG = "Island.PIS"