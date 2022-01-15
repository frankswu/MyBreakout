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

import android.opengl.Matrix
import android.util.Log
import com.frank.breakout.activity.BreakoutActivity.Companion.TAG
import com.frank.breakout.utils.OpenGLUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

typealias DrawContentFunction = () -> Unit
typealias SetProgramHandleFunction = (glProgram: Int) -> Unit
typealias InitParamsHandleFunction = () -> Unit
/**
 * Base class for our graphical objects.
 */
abstract class BaseRect protected constructor() {
    /*
     * We keep track of position and scale (size) here.  Rather than holding these in fields
     * and copying them to the model/view matrix as needed, we just use the matrix as storage.
     */
    // Handles to uniforms and attributes in the shader.
    protected var sProgramHandle = -1
    protected var sPositionHandle = -1

    /**
     * Model/view matrix for this object.  Updated by setPosition() and setScale().  This
     * should be merged with the projection matrix when it's time to draw the object.
     */
    @JvmField
    protected var mModelView = FloatArray(16)
    /**
     * Returns the X position (arena / world coordinates).
     */// column-major 4x4 matrix
    /**
     * Sets the position in the arena (X-coord only).
     */
    var xPosition: Float
        get() = mModelView[12]
        set(value) {
            mModelView[12] = value
        }

    /**
     * Returns the Y position (arena / world coordinates).
     */
    fun getYPosition() = mModelView[13]

    /**
     * Sets the position in the arena.
     */
    fun setPosition(x: Float, y: Float) {
        // column-major 4x4 matrix
        mModelView[12] = x
        mModelView[13] = y
    }

    /**
     * Gets the scale value in the X dimension.
     */
    fun getXScale() = mModelView[0]

    /**
     * Gets the scale value in the Y dimension.
     */
    fun getYScale() = mModelView[5]

    /**
     * Sets the size of the rectangle.
     */
    fun setScale(xs: Float, ys: Float) {
        // column-major 4x4 matrix
        mModelView[0] = xs
        mModelView[5] = ys
    }


    override fun toString(): String {
        return ("[BaseRect x=$xPosition y=${getYPosition()} xs=${getXScale()} ys=${getYScale()}]")
    }

    companion object {
        /**
         * Simple square, specified as a triangle strip.  The square is centered on (0,0) and has
         * a size of 1x1.
         *
         *
         * Triangles are 0-1-2 and 2-1-3 (counter-clockwise winding).
         */
        private val COORDS = floatArrayOf(
            -0.5f, -0.5f,  // 0 bottom left
            0.5f, -0.5f,  // 1 bottom right
            -0.5f, 0.5f,  // 2 top left
            0.5f, 0.5f
        )

        /**
         * Texture coordinates.  These are flipped upside-down to match pixel data that starts
         * at the top left (typical of many image formats).
         */
        private val TEX_COORDS = floatArrayOf(
            0.0f, 1.0f,  // bottom left
            1.0f, 1.0f,  // bottom right
            0.0f, 0.0f,  // top left
            1.0f, 0.0f
        )

        /**
         * Square, suitable for GL_LINE_LOOP.  (The standard COORDS will create an hourglass.)
         * This is expected to have the same number of vertices and coords per vertex as COORDS.
         */
        private val OUTLINE_COORDS = floatArrayOf(
            -0.5f, -0.5f,  // bottom left
            0.5f, -0.5f,  // bottom right
            0.5f, 0.5f,  // top right
            -0.5f, 0.5f
        )


        /**
         * Returns a FloatBuffer with the vertex data for a unit-size square.  The vertices are
         * arranged for use with a ccw triangle strip.
         */
        // Common arrays of vertices.
        val sVertexArray = createVertexArray(COORDS)

        /**
         * Returns a FloatBuffer with the texture coordinate data for an image with (0,0) in the
         * top-left corner.
         */
        val sTexArray = createVertexArray(TEX_COORDS)

        /**
         * Returns a FloatBuffer with vertex data suitable for an outline rect (which has to
         * specify the vertices in a different order).
         */
        val sOutlineVertexArray = createVertexArray(OUTLINE_COORDS)
        const val COORDS_PER_VERTEX = 2 // x,y
        const val TEX_COORDS_PER_VERTEX = 2 // s,t
        const val VERTEX_STRIDE = COORDS_PER_VERTEX * 4 // 4 bytes per float
        const val TEX_VERTEX_STRIDE = TEX_COORDS_PER_VERTEX * 4

        // vertex count should be the same for both COORDS and TEX_COORDS
        @JvmField
        val VERTEX_COUNT = COORDS.size / COORDS_PER_VERTEX

        /**
         * Allocates a direct float buffer, and populates it with the vertex data.
         */
        private fun createVertexArray(vertexArray: FloatArray): FloatBuffer {
            // Allocate a direct ByteBuffer, using 4 bytes per float, and copy coords into it.
            val fb = ByteBuffer.allocateDirect(vertexArray.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            fb.put(vertexArray).position(0)
            return fb
        }

        @JvmStatic
        protected fun createOpenGLProgram(
            vertexShaderCode: String,
            fragmentShaderCode: String
        ): Int {
            var sProgramHandle = OpenGLUtil.createProgram(
                vertexShaderCode,
                fragmentShaderCode
            )
            Log.d(TAG, "Created program $sProgramHandle")
            return sProgramHandle
        }

        fun createProgram(
            vertexShaderCode: String,
            fragmentShaderCode: String,
            setProgramHandleFunction: SetProgramHandleFunction,
            initParamsHandleFunction: InitParamsHandleFunction
        ) {
            //sProgramHandle = createOpenGLProgram(vertexShaderCode, fragmentShaderCode)
            setProgramHandleFunction.invoke(createOpenGLProgram(vertexShaderCode, fragmentShaderCode))
            initParamsHandleFunction.invoke()
        }

        private fun setProgramHandle(glProgram: Int) {
            TODO("Not yet implemented")
        }

        private fun initParamsHandle() {
            TODO("Not yet implemented")
        }

    }

    init {
        // Init model/view matrix, which holds position and scale.
        Matrix.setIdentityM(mModelView, 0)
    }
}