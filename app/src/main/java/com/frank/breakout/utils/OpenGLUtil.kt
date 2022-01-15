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
package com.frank.breakout.utils

import android.opengl.GLES20
import android.opengl.GLES20.GL_SHADING_LANGUAGE_VERSION
import android.opengl.GLES20.GL_VENDOR
import android.util.Log
import com.frank.breakout.activity.BreakoutActivity.Companion.TAG
import java.nio.ByteBuffer
import javax.microedition.khronos.opengles.GL10


/**
 * A handful of utility functions.
 */
object OpenGLUtil {

    /**
     * Creates a texture from raw data.
     *
     * @param data Image data.
     * @param width Texture width, in pixels (not bytes).
     * @param height Texture height, in pixels.
     * @param format Image data format (use constant appropriate for glTexImage2D(), e.g. GL_RGBA).
     * @return Handle to texture.
     */
    fun createImageTexture(data: ByteBuffer?, width: Int, height: Int, format: Int): Int {
        val textureHandles = IntArray(1)
        GLES20.glGenTextures(1, textureHandles, 0)
        val textureHandle: Int = textureHandles[0]
        checkGlError("glGenTextures")

        // Bind the texture handle to the 2D texture target.
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle)

        // Configure min/mag filtering, i.e. what scaling method do we use if what we're rendering
        // is smaller or larger than the source image.
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        checkGlError("loadImageTexture")

        // Load the data from the buffer into the texture handle.
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,  /*level*/0, format,
            width, height,  /*border*/0, format, GLES20.GL_UNSIGNED_BYTE, data
        )
        checkGlError("loadImageTexture")
        return textureHandle
    }

    /**
     * Loads a shader from a string and compiles it.
     *
     * @param type GL shader type, e.g. GLES20.GL_VERTEX_SHADER.
     * @param shaderCode Shader source code.
     * @return Handle to shader.
     */
    private fun loadShader(type: Int, shaderCode: String?): Int {
        val shaderHandle = GLES20.glCreateShader(type)
        if (shaderHandle != 0) {
            GLES20.glShaderSource(shaderHandle, shaderCode)
            GLES20.glCompileShader(shaderHandle)

            // Check for failure.
            val compileStatus = IntArray(1)
            GLES20.glGetShaderiv(shaderHandle, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
            if (compileStatus[0] != GLES20.GL_TRUE) {
                // Extract the detailed failure message.
                val msg = GLES20.glGetShaderInfoLog(shaderHandle)
                GLES20.glDeleteProgram(shaderHandle)
                Log.e(TAG, "glCompileShader: $msg")
                throw RuntimeException("glCompileShader failed")
            } else {
                Log.i(TAG, "create shader  is success:$shaderHandle");
            }
        } else {
            Log.i(TAG, "create shader  is error:$type=>$shaderCode");
        }
        return shaderHandle
    }

    /**
     * Creates a program, given source code for vertex and fragment shaders.
     *
     * @param vertexShaderCode Source code for vertex shader.
     * @param fragmentShaderCode Source code for fragment shader.
     * @return Handle to program.
     */
    fun createProgram(vertexShaderCode: String?, fragmentShaderCode: String?): Int {
        printOpenGLInfo()
        // Load the shaders.
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        // Build the program.
        val programHandle = GLES20.glCreateProgram()
        if (programHandle != 0) {
            GLES20.glAttachShader(programHandle, vertexShader)
            GLES20.glAttachShader(programHandle, fragmentShader)
            GLES20.glLinkProgram(programHandle)

            // Check for failure.
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(programHandle, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] != GLES20.GL_TRUE) {
                // Extract the detailed failure message.
                val msg = GLES20.glGetProgramInfoLog(programHandle)
                GLES20.glDeleteProgram(programHandle)
                Log.e(TAG, "glLinkProgram: $msg")
                throw RuntimeException("glLinkProgram failed")
            } else {
                Log.i(TAG, "create program is success:$programHandle")
            }
        } else {
            Log.i(TAG, "create program is error:$vertexShaderCode:$fragmentShaderCode")
        }

        return programHandle
    }

    private fun printOpenGLInfo() {
        val extensions = GLES20.glGetString(GL10.GL_EXTENSIONS)
        val version = GLES20.glGetString(GL10.GL_VERSION)
        val glslVersion = GLES20.glGetString(GL_SHADING_LANGUAGE_VERSION)
        val vendor = GLES20.glGetString(GL_VENDOR)
        Log.i(
            TAG,
            """
                    The version format is displayed as:  OpenGL ES <major>.<minor>)
                    {$version}
                    The shading language version 
                    {$glslVersion}
                    The vendor: 
                    {$vendor}
                    android OpenGL ES has extensions {$extensions}
                    """.trimIndent()
        )
    }

    /**
     * Utility method for checking for OpenGL errors.  Use like this:
     * https://learnopengl-cn.readthedocs.io/zh/latest/06%20In%20Practice/01%20Debugging/
     * <pre>
     * mColorHandle = GLES20.glGetUniformLocation(mProgram, "vColor");
     * MyGLRenderer.checkGlError("glGetUniformLocation");</pre>
     *
     * If an error was detected, this will throw an exception.
     *
     * @param msg string to display in the error message (usually the name of the last
     * GL operation)
     */
    @JvmStatic
    fun checkGlError(msg: String) {
        //当你不正确使用OpenGL的时候（比如说在绑定之前配置一个缓冲），它会检测到，并在幕后生成一个或多个用户错误标记。
        // 我们可以使用一个叫做glGetError的函数查询这些错误标记。，他会检测错误标记集，并且在OpenGL确实出错的时候返回一个错误值。
        //当你不正确使用OpenGL的时候（比如说在绑定之前配置一个缓冲），它会检测到，并在幕后生成一个或多个用户错误标记。
        // 我们可以使用一个叫做glGetError的函数查询这些错误标记。，他会检测错误标记集，并且在OpenGL确实出错的时候返回一个错误值。
        val error = GLES20.glGetError()
        Log.i(TAG, "check gl error info: {$error}")
        if (error != GLES20.GL_NO_ERROR) {
            throw java.lang.RuntimeException("$msg: glError=>$error ")
        }
    }

}