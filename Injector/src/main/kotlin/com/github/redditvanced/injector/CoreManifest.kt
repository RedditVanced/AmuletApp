package com.github.redditvanced.injector

/**
 * Serializable json class for the manifest.json found in the core build
 * TODO: move this + core's to a shared common lib w/ all the typealiases as well
 */
data class CoreManifest(
	/**
	 * The version of the core
	 */
	val coreVersion: String,

	/**
	 * The supported version code of the reddit app
	 */
	val redditVersionCode: Int,

	/**
	 * The supported version name of the reddit app
	 */
	val redditVersionName: String,
)
