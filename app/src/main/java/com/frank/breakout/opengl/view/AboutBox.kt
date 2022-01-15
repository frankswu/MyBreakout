/*
 * Copyright 2012 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.frank.breakout.opengl.view

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.view.InflateException
import android.view.View
import com.frank.breakout.R
import com.frank.breakout.activity.BreakoutActivity.Companion.TAG

/**
 * Creates and displays an "about" box.
 */
object AboutBox {

    /**
     * Retrieves the application's version string.
     */
    private fun getVersionString(context: Context): String {
        val pman = context.packageManager
        val packageName = context.packageName
        return try {
            val pinfo = pman.getPackageInfo(packageName, 0)
            Log.d(TAG, "Found version ${pinfo.versionName} for $packageName")
            pinfo.versionName
        } catch (nnfe: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Unable to retrieve package info for $packageName")
            "(unknown)"
        }
    }

    /**
     * Displays the About box.  An AlertDialog is created in the calling activity's context.
     *
     *
     * The box will disappear if the "OK" button is touched, if an area outside the box is
     * touched, if the screen is rotated ... doing just about anything makes it disappear.
     */
    fun display(caller: Activity) {
        val versionStr = getVersionString(caller)
        val aboutHeader = caller.getString(R.string.app_name) + " v" + versionStr

        // Manually inflate the view that will form the body of the dialog.
        val aboutView: View
        try {
            aboutView = caller.layoutInflater.inflate(R.layout.about, null)
        } catch (ie: InflateException) {
            Log.e(TAG, "Exception while inflating about box: ${ie.message}")
            return
        }
        val builder = AlertDialog.Builder(caller)
        builder.setTitle(aboutHeader)
        builder.setIcon(R.mipmap.ic_launcher_round)
        builder.setCancelable(true) // implies setCanceledOnTouchOutside
        builder.setPositiveButton(R.string.ok, null)
        builder.setView(aboutView)
        builder.show()
    }
}