/*
 * Copyright (c) 2026 Jonas Vautherin
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package ch.vautherin.ap_sitl

object SitlNative {
    init {
        System.loadLibrary("sitl_jni")
    }

    /** Runs the vehicle's main loop on the calling thread. Does not return. */
    @JvmStatic
    external fun run(library: String, workDir: String, logPath: String, args: Array<String>): Int
}
