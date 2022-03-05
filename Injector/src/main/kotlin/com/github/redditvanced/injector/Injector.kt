package com.github.redditvanced.injector

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.beust.klaxon.Klaxon
import com.github.redditvanced.common.*
import com.github.redditvanced.common.Constants.PROJECT_NAME
import com.github.redditvanced.common.Constants.Paths.CORE_SETTINGS
import com.github.redditvanced.common.Constants.Paths.CUSTOM_CORE
import com.github.redditvanced.common.models.CoreManifest
import com.github.redditvanced.common.models.CoreSettings
import com.reddit.frontpage.main.MainActivity
import com.reddit.frontpage.ui.HomePagerScreen
import dalvik.system.BaseDexClassLoader
import top.canyie.pine.Pine
import top.canyie.pine.PineConfig
import top.canyie.pine.callback.MethodHook
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import kotlin.concurrent.thread
import kotlin.system.exitProcess

object Injector {
	const val LOG_TAG = "Injector"
	private val klaxon = Klaxon()

	fun preInit(activity: MainActivity) {
		var hook: MethodHook.Unhook? = null
		hook = Pine.hook(
			activity::class.java.getDeclaredMethod("onCreate", Bundle::class.java),
			object : MethodHook() {
				override fun beforeCall(callFrame: Pine.CallFrame) {
					init(callFrame.thisObject as MainActivity)
					hook?.unhook()
					hook = null
				}
			})
	}

	fun init(activity: MainActivity) {
		PineConfig.debug = File(Constants.Paths.BASE, ".pine_debug").exists()
		PineConfig.debuggable = File(Constants.Paths.BASE, ".debuggable").exists()
		PineConfig.disableHiddenApiPolicy = false
		PineConfig.disableHiddenApiPolicyForPlatformDomain = false
		Pine.disableJitInline()
		Pine.disableProfileSaver()

		if (!requestPermissions(activity))
			return

		// Pine's disableHiddenApiPolicy crashes according to Juby
		HiddenAPIPolicy.disableHiddenApiPolicy()

		Log.i(LOG_TAG, "Initializing $PROJECT_NAME...")
		try {
			initCore(activity)
		} catch (t: Throwable) {
			errorToast("Failed to initialize $PROJECT_NAME", activity, t)
		}
	}

	private fun initCore(activity: MainActivity) {
		if (!pruneArtProfile(activity))
			Log.w(LOG_TAG, "Failed to prune ART profile!")

		File(Constants.Paths.PLUGINS).mkdirs()
		File(Constants.Paths.THEMES).mkdir()
		File(Constants.Paths.CRASHLOGS).mkdir()

		if (!CORE_SETTINGS.exists()) {
			CORE_SETTINGS.createNewFile()
			// TODO: remove useCustomCore=true
			klaxon.toJsonFile(CoreSettings(useCustomCore = true), CORE_SETTINGS)
		}

		val cachedCoreFile = File(activity.codeCacheDir, "core.zip")
		val coreSettings = requireNotNull(klaxon.parse<CoreSettings>(CORE_SETTINGS)) {
			"Failed to parse core settings!"
		}

		try {
			val coreFile = if (coreSettings.useCustomCore && CUSTOM_CORE.exists()) {
				Log.d(LOG_TAG, "Loading custom core from ${CUSTOM_CORE.absolutePath}")
				CUSTOM_CORE
			} else if (coreSettings.useCustomCore) {
				Log.w(LOG_TAG, "Custom core missing, using default...")
				cachedCoreFile
			} else cachedCoreFile

			// Download default core to cache dir
			if (!coreSettings.useCustomCore && !cachedCoreFile.exists()) {
				Log.d(LOG_TAG, "Downloading core from github...")
				val thread = Thread {
					// TODO: download core
				}
				thread.start()
				thread.join()
				Log.d(LOG_TAG, "Finished downloading core from github...")

				val clientVersion = FrontpageSettings.i.appVersionCode
				Log.d(LOG_TAG, "Retrieved local Reddit version: $clientVersion")

				val zip = ZipFile(coreFile)
				val manifestStream = zip.getInputStream(zip.getEntry("manifest.json"))
				val manifest = klaxon.parse<CoreManifest>(manifestStream)
					?: throw IllegalStateException("Failed to parse core manifest")
				zip.close()
				Log.d(LOG_TAG, "Retrieved supported Reddit version: ${manifest.redditVersionCode}")

				if (manifest.redditVersionCode > clientVersion) {
					errorToast("Your base Reddit is outdated. Please reinstall using the Installer", activity, null)
				}
			}

			Log.d(LOG_TAG, "Adding core to the classpath...")
			addDexToClasspath(coreFile, activity.classLoader)
			Log.d(LOG_TAG, "Successfully added core to the classpath.")

			val c = Class.forName("com.github.redditvanced.core.Main")
			val init = c.getDeclaredMethod("init", MainActivity::class.java)
			Log.d(LOG_TAG, "Invoking main core entry point...")
			init.invoke(null, activity)
			Log.d(LOG_TAG, "Finished initializing core.")
		} catch (t: Throwable) {
			// Delete file so it is reinstalled the next time
			if (!coreSettings.useCustomCore)
				cachedCoreFile.delete()
			errorToast("Failed to load $PROJECT_NAME", activity, t)
		}
	}

	private fun errorToast(msg: String, activity: Activity, throwable: Throwable? = null) {
		Log.e(LOG_TAG, msg, throwable)
		Handler(Looper.getMainLooper()).post {
			Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
		}
	}

	@SuppressLint("DiscouragedPrivateApi") // this private api seems to be stable, thanks to facebook who use it in the facebook app
	private fun addDexToClasspath(dex: File, classLoader: ClassLoader) {
		// https://android.googlesource.com/platform/libcore/+/58b4e5dbb06579bec9a8fc892012093b6f4fbe20/dalvik/src/main/java/dalvik/system/BaseDexClassLoader.java#59
		val pathListField = BaseDexClassLoader::class.java.getDeclaredField("pathList")
			.apply { isAccessible = true }
		val pathList = pathListField[classLoader]!!
		val addDexPath = pathList.javaClass.getDeclaredMethod("addDexPath", String::class.java, File::class.java)
			.apply { isAccessible = true }
		addDexPath.invoke(pathList, dex.absolutePath, null)
	}

	/**
	 * Try to prevent method inlining by deleting the usage profile used by AOT compilation
	 * https://source.android.com/devices/tech/dalvik/configure#how_art_works
	 */
	private fun pruneArtProfile(activity: MainActivity): Boolean {
		Log.d(LOG_TAG, "Pruning ART usage profile...")
		val profile = File("/data/misc/profiles/cur/0/" + activity.packageName + "/primary.prof")
		if (!profile.exists()) {
			return false
		}
		if (profile.length() > 0) {
			try {
				// Delete file contents
				FileOutputStream(profile).close()
			} catch (ignored: Throwable) {
				return false
			}
		}
		return true
	}

	// TODO: api >30??
	@SuppressLint("NewApi")
	private fun requestPermissions(injectorActivity: MainActivity): Boolean {
		if (Environment.isExternalStorageManager())
			return true

		val mHomePage = HomePagerScreen::class.java.getDeclaredConstructor()
		val mOnResume = Activity::class.java.getDeclaredMethod("onResume")
		val mActivityCreate = Activity::class.java.getDeclaredMethod("onCreate", Bundle::class.java)

		// TODO: find way to get activity from HomePagerScreen
		var appActivity: Activity? = null
		val activityUnpatch = Pine.hook(mActivityCreate, object : MethodHook() {
			override fun afterCall(frame: Pine.CallFrame) {
				appActivity = frame.thisObject as Activity
			}
		})

		var homePageUnpatch: MethodHook.Unhook? = null
		homePageUnpatch = Pine.hook(mHomePage, object : MethodHook() {
			override fun afterCall(frame: Pine.CallFrame) {
				homePageUnpatch!!.unhook()
				activityUnpatch!!.unhook()

				var onResumeUnpatch: MethodHook.Unhook? = null
				onResumeUnpatch = Pine.hook(mOnResume, object : MethodHook() {
					override fun afterCall(frame: Pine.CallFrame) {
						val activity = frame.thisObject as Activity
						onResumeUnpatch!!.unhook()

						if (!Environment.isExternalStorageManager()) thread(true) {
							errorToast("Manage storage permissions are required for RedditVanced to load!", activity)
							Thread.sleep(4200)
							errorToast("Please go into your settings and enable it.", activity)
						} else {
							val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
							activity.startActivity(Intent.makeRestartActivityTask(intent!!.component))
							exitProcess(0)
						}
					}
				})

				val intent = Intent(
					Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
					Uri.parse("package:${injectorActivity.packageName}")
				)
				appActivity!!.startActivity(intent)
				appActivity = null
			}
		})

		return false
	}
}
