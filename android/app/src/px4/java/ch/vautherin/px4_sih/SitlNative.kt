/*
 * Copyright (c) 2026 Jonas Vautherin
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package ch.vautherin.px4_sih

object SitlNative {
    init {
        System.loadLibrary("px4_jni")
    }

    /**
     * Runs PX4 on the calling thread. Does not return.
     *
     * @param environment names and values in turn
     * @param args the command line of the px4 program
     */
    @JvmStatic
    external fun run(logPath: String, environment: Array<String>, args: Array<String>): Int
}
