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
package com.frank.breakout.opengl.base

import android.graphics.Rect
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import com.frank.breakout.activity.BreakoutActivity.Companion.TAG
import com.frank.breakout.opengl.view.GameSurfaceRenderer
import com.frank.breakout.utils.OpenGLUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Represents a two-dimensional axis-aligned textured rectangle.
 */
open class TexturedAlignedRect : BaseRect() {
    // Texture data for this instance.
    private var mTextureDataHandle = -1
    private var mTextureWidth = -1
    private var mTextureHeight = -1
    private val mTexBuffer: FloatBuffer

    /**
     * Sets the texture data by creating a new texture from a buffer of data.
     */
    fun setTexture(buf: ByteBuffer?, width: Int, height: Int, format: Int) {
        mTextureDataHandle = OpenGLUtil.createImageTexture(buf, width, height, format)
        mTextureWidth = width
        mTextureHeight = height
    }

    /**
     * Sets the texture data to the specified texture handle.
     *
     * @param handle GL texture handle.
     * @param width Width of the texture (in texels).
     * @param height Height of the texture (in texels).
     */
    fun setTexture(handle: Int, width: Int, height: Int) {
        mTextureDataHandle = handle
        mTextureWidth = width
        mTextureHeight = height
    }

    /**
     * Specifies the rectangle within the texture map where the texture data is.  By default,
     * the entire texture will be used.
     *
     *
     * Texture coordinates use the image coordinate system, i.e. (0,0) is in the top left.
     * Remember that the bottom-right coordinates are exclusive.
     *
     * @param coords Coordinates within the texture.
     */
    fun setTextureCoords(coords: Rect) {
        // Convert integer rect coordinates to [0.0, 1.0].
        val left = coords.left.toFloat() / mTextureWidth
        val right = coords.right.toFloat() / mTextureWidth
        val top = coords.top.toFloat() / mTextureHeight
        val bottom = coords.bottom.toFloat() / mTextureHeight
        with(mTexBuffer) {
            put(left) // bottom left
            put(bottom)
            put(right) // bottom right
            put(bottom)
            put(left) // top left
            put(top)
            put(right) // top right
            put(top)
            position(0)
        }
    }

    /**
     * Draws the textured rect.
     */
    fun draw() {
        if (GameSurfaceRenderer.EXTRA_CHECK) OpenGLUtil.checkGlError("draw start")
        if (!sDrawPrepared) {
            throw RuntimeException("not prepared")
        }

        // Connect mTexBuffer to "a_texCoord".
        GLES20.glVertexAttribPointer(
            sTexCoordHandle, TEX_COORDS_PER_VERTEX,
            GLES20.GL_FLOAT, false, TEX_VERTEX_STRIDE, mTexBuffer
        )
        if (GameSurfaceRenderer.EXTRA_CHECK) OpenGLUtil.checkGlError("glVertexAttribPointer")

        // Compute model/view/projection matrix.
        val mvp = sTempMVP // scratch storage
        Matrix.multiplyMM(mvp, 0, GameSurfaceRenderer.mProjectionMatrix, 0, mModelView, 0)

        // Copy the model / view / projection matrix over.
        GLES20.glUniformMatrix4fv(sMVPMatrixHandle, 1, false, mvp, 0)
        if (GameSurfaceRenderer.EXTRA_CHECK) OpenGLUtil.checkGlError("glUniformMatrix4fv")

        // Set the active texture unit to unit 0.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        if (GameSurfaceRenderer.EXTRA_CHECK) OpenGLUtil.checkGlError("glActiveTexture")

        // In OpenGL ES 1.1 you needed to call glEnable(GLES20.GL_TEXTURE_2D).  This is not
        // required in 2.0, and will actually raise a GL_INVALID_ENUM error.

        // Bind the texture data to the 2D texture target.
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextureDataHandle)
        if (GameSurfaceRenderer.EXTRA_CHECK) OpenGLUtil.checkGlError("glBindTexture")

        // Draw the rect.
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, VERTEX_COUNT)
        if (GameSurfaceRenderer.EXTRA_CHECK) OpenGLUtil.checkGlError("glDrawArrays")
    }

    companion object {

        /*
                    "uniform mat4 u_mvpMatrix;" +  // model/view/projection matrix
                            "attribute vec4 a_position;" +  // vertex data for us to transform
                            "attribute vec2 a_texCoord;" +  // texture coordinate for vertex...
                            "varying vec2 v_texCoord;" +  // ...which we forward to the fragment shader
                            "void main() {" +
                            "  gl_Position = u_mvpMatrix * a_position;" +
                            "  v_texCoord = a_texCoord;" +
                            "}"
        */
        /*
     * Similar to BasicAlignedRect, but we need to manage texture data as well.
     */
        private const val VERTEX_SHADER_CODE = """
            uniform mat4 u_mvpMatrix;
            attribute vec4 a_position;
            attribute vec2 a_texCoord;
            varying vec2 v_texCoord;
            void main() {
              gl_Position = u_mvpMatrix * a_position;
              v_texCoord = a_texCoord;
            }
        """

        private const val FRAGMENT_SHADER_CODE = """
            precision mediump float;
            uniform sampler2D u_texture;
            varying vec2 v_texCoord;
            void main() {
              gl_FragColor = texture2D(u_texture, v_texCoord);
            }
        """
/*
            "precision mediump float;" +  // medium is fine for texture maps
                    "uniform sampler2D u_texture;" +  // texture data
                    "varying vec2 v_texCoord;" +  // linearly interpolated texture coordinate
                    "void main() {" +
                    "  gl_FragColor = texture2D(u_texture, v_texCoord);" +
                    "}"
*/

        // References to vertex data.
        private val sVertexBuffer: FloatBuffer = sVertexArray

        // Handles to uniforms and attributes in the shader.
        private var sProgramHandle = -1
        private var sPositionHandle = -1
        private var sTexCoordHandle = -1
        private var sMVPMatrixHandle = -1

        // Sanity check on draw prep.
        private var sDrawPrepared = false

        /*
     * Scratch storage for the model/view/projection matrix.  We don't actually need to retain
     * it between calls, but we also don't want to re-allocate space for it every time we draw
     * this object.
     *
     * Because all of our rendering happens on a single thread, we can make this static instead
     * of per-object.  To avoid clashes within a thread, this should only be used in draw().
     */
        private val sTempMVP = FloatArray(16)

        /**
         * Creates the GL program and associated references.
         */
        fun createProgram() {
            createProgram(VERTEX_SHADER_CODE, FRAGMENT_SHADER_CODE, { sProgramHandle = it }) {
                initParamsHandle()
            }
/*
            sProgramHandle = createOpenGLProgram(VERTEX_SHADER_CODE, FRAGMENT_SHADER_CODE)
            initParamsHandle()
*/
        }

        private fun initParamsHandle() {
            // Get handle to vertex shader's a_position member.
            sPositionHandle = GLES20.glGetAttribLocation(sProgramHandle, "a_position")
            OpenGLUtil.checkGlError("glGetAttribLocation")

            // Get handle to vertex shader's a_texCoord member.
            sTexCoordHandle = GLES20.glGetAttribLocation(sProgramHandle, "a_texCoord")
            OpenGLUtil.checkGlError("glGetAttribLocation")

            // Get handle to transformation matrix.
            sMVPMatrixHandle = GLES20.glGetUniformLocation(sProgramHandle, "u_mvpMatrix")
            OpenGLUtil.checkGlError("glGetUniformLocation")

            // Get handle to texture reference.
            val textureUniformHandle = GLES20.glGetUniformLocation(sProgramHandle, "u_texture")
            OpenGLUtil.checkGlError("glGetUniformLocation")

            // Set u_texture to reference texture unit 0.  (We don't change the value, so we can just
            // set it here.)
            GLES20.glUseProgram(sProgramHandle)
            GLES20.glUniform1i(textureUniformHandle, 0)
            OpenGLUtil.checkGlError("glUniform1i")
            GLES20.glUseProgram(0)
            OpenGLUtil.checkGlError("TexturedAlignedRect setup complete")
        }

        /**
         * Performs setup common to all BasicAlignedRects.
         */
        private fun prepareToDraw() {
            // Select our program.
            GLES20.glUseProgram(sProgramHandle)
            OpenGLUtil.checkGlError("glUseProgram")

            // Enable the "a_position" vertex attribute.
            GLES20.glEnableVertexAttribArray(sPositionHandle)
            OpenGLUtil.checkGlError("glEnableVertexAttribArray")

            // Connect sVertexBuffer to "a_position".
            GLES20.glVertexAttribPointer(
                sPositionHandle, COORDS_PER_VERTEX,
                GLES20.GL_FLOAT, false, VERTEX_STRIDE, sVertexBuffer
            )
            OpenGLUtil.checkGlError("glEnableVertexAttribPointer")

            // Enable the "a_texCoord" vertex attribute.
            GLES20.glEnableVertexAttribArray(sTexCoordHandle)
            OpenGLUtil.checkGlError("glEnableVertexAttribArray")
            sDrawPrepared = true
        }

        /**
         * Cleans up after drawing.
         */
        private fun finishedDrawing() {
            sDrawPrepared = false

            // Disable vertex array and program.  Not strictly necessary.
            GLES20.glDisableVertexAttribArray(sPositionHandle)
            GLES20.glUseProgram(0)
        }

        fun drawContent(function: DrawContentFunction) {
            prepareToDraw()
            function.invoke()
            finishedDrawing()
        }

    }

    init {
        val defaultCoords: FloatBuffer = sTexArray

        // Allocate a FloatBuffer to hold our texture coordinate data, and populate it with
        // default values.  These may be overwritten by setTextureCoords().
        val bb = ByteBuffer.allocateDirect(VERTEX_COUNT * TEX_VERTEX_STRIDE).order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(defaultCoords)
        defaultCoords.position(0) // ugh
        fb.position(0)
        mTexBuffer = fb
    }



}