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
package com.frank.breakout.opengl

import android.opengl.GLES20
import android.opengl.Matrix
import com.frank.breakout.opengl.base.BasicAlignedRect
import com.frank.breakout.opengl.view.GameSurfaceRenderer
import com.frank.breakout.utils.OpenGLUtil

/**
 * A rectangle drawn as an outline rather than filled.  Useful for debugging.
 */
class OutlineAlignedRect : BasicAlignedRect() {
    override fun draw() {
        if (GameSurfaceRenderer.EXTRA_CHECK) OpenGLUtil.checkGlError("draw start")
        if (!sDrawPrepared) {
            throw RuntimeException("not prepared")
        }

        // Compute model/view/projection matrix.
        val mvp = sTempMVP // scratch storage
        Matrix.multiplyMM(mvp, 0, GameSurfaceRenderer.mProjectionMatrix, 0, mModelView, 0)

        // Copy the model / view / projection matrix over.
        GLES20.glUniformMatrix4fv(sMVPMatrixHandle, 1, false, mvp, 0)
        OpenGLUtil.checkGlError("glUniformMatrix4fv")

        // Copy the color vector into the program.
        GLES20.glUniform4fv(sColorHandle, 1, color, 0)
        OpenGLUtil.checkGlError("glUniform4fv ")

        // Draw the rect.
        GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, VERTEX_COUNT)
        if (GameSurfaceRenderer.EXTRA_CHECK) OpenGLUtil.checkGlError("glDrawArrays")
    }

    companion object {
        private val sOutlineVertexBuffer = sOutlineVertexArray

        // Sanity check on draw prep.
        private var sDrawPrepared = false

        /**
         * Performs setup common to all BasicAlignedRects.
         */
        fun prepareToDraw() {
            // Set the program.  We use the same one as BasicAlignedRect.
            GLES20.glUseProgram(sProgramHandle)
            OpenGLUtil.checkGlError("glUseProgram")

            // Enable the "a_position" vertex attribute.
            GLES20.glEnableVertexAttribArray(sPositionHandle)
            OpenGLUtil.checkGlError("glEnableVertexAttribArray")

            // Connect sOutlineVertexBuffer to "a_position".
            GLES20.glVertexAttribPointer(
                sPositionHandle, COORDS_PER_VERTEX,
                GLES20.GL_FLOAT, false, VERTEX_STRIDE, sOutlineVertexBuffer
            )
            OpenGLUtil.checkGlError("glVertexAttribPointer")
            sDrawPrepared = true
        }

        /**
         * Cleans up after drawing.
         */
        fun finishedDrawing() {
            sDrawPrepared = false

            // Disable vertex array and program.  Not strictly necessary.
            GLES20.glDisableVertexAttribArray(sPositionHandle)
            GLES20.glUseProgram(0)
        }
    }
}