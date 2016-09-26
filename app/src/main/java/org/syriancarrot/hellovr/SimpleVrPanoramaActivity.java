/*
 * Copyright 2015 Google Inc. All Rights Reserved.

 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.syriancarrot.hellovr;

import android.content.pm.ActivityInfo;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Bundle;
import android.util.Log;

import com.google.vr.sdk.base.Eye;
import com.google.vr.sdk.base.GvrActivity;
import com.google.vr.sdk.base.GvrView;
import com.google.vr.sdk.base.HeadTransform;
import com.google.vr.sdk.base.Viewport;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * A basic PanoWidget Activity to load panorama images from disk. It will load a test image by
 * default. It can also load an arbitrary image from disk using:
 *   adb shell am start -a "android.intent.action.VIEW" \
 *     -n "com.google.vr.sdk.samples.simplepanowidget/.SimpleVrPanoramaActivity" \
 *     -d "/sdcard/FILENAME.JPG"
 *
 * To load stereo images, "--ei inputType 2" can be used to pass in an integer extra which will set
 * VrPanoramaView.Options.inputType.
 */
public class SimpleVrPanoramaActivity extends GvrActivity implements GvrView.StereoRenderer, SurfaceTexture.OnFrameAvailableListener {

  private static final String TAG = "VRCamMtMMainAc";
  private static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65;
  private static Camera camera = null;


  private FloatBuffer vertexBuffer, textureVerticesBuffer, vertexBuffer2;
  private ShortBuffer drawListBuffer, buf2;
  private int mProgram;
  private int mPositionHandle, mPositionHandle2;
  private int mColorHandle;
  private int mTextureCoordHandle;


  // number of coordinates per vertex in this array
  static final int COORDS_PER_VERTEX = 2;
  static float squareVertices[] = { // in counterclockwise order:
          -1.0f, -1.0f,   // 0.left - mid
          1.0f, -1.0f,   // 1. right - mid
          -1.0f, 1.0f,   // 2. left - top
          1.0f, 1.0f,   // 3. right - top

  };


  private short drawOrder[] = {0, 2, 1, 1, 2, 3}; // order to draw vertices
  private short drawOrder2[] = {2, 0, 3, 3, 0, 1}; // order to draw vertices

  static float textureVertices[] = {
          0.0f, 1.0f,  // A. left-bottom
          1.0f, 1.0f,  // B. right-bottom
          0.0f, 0.0f,  // C. left-top
          1.0f, 0.0f   // D. right-top
  };

  private final int vertexStride = COORDS_PER_VERTEX * 4; // 4 bytes per vertex

  private ByteBuffer indexBuffer;    // Buffer for index-array

  private int texture;
  private CardboardOverlayView mOverlayView;
  private GvrView cardboardView;
  private SurfaceTexture surface;
  private float[] mView;
  private float[] mCamera;

  public void startCamera(int texture) {
    surface = new SurfaceTexture(texture);
    surface.setOnFrameAvailableListener(this);

      //CALL FROM YOUR ACTIVITY**
      MyPermissions requestUserPermission = new MyPermissions(this);
      requestUserPermission.verifyStoragePermissions();

    camera = Camera.open();
    Camera.Parameters params = camera.getParameters();
    params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);

    // todo Check what resolutions are supported by your camera
    camera.setParameters(params);
    try {
      camera.setPreviewTexture(surface);
      camera.startPreview();
    } catch (IOException ioe)
    {
      Log.i(TAG, "CAM LAUNCH FAILED");
    }

  }

  static private int createTexture() {
    int[] texture = new int[1];

    GLES20.glGenTextures(1, texture, 0);
    GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, texture[0]);
    GLES20.glTexParameterf(GL_TEXTURE_EXTERNAL_OES,
            GL10.GL_TEXTURE_MIN_FILTER, GL10.GL_LINEAR);
    GLES20.glTexParameterf(GL_TEXTURE_EXTERNAL_OES,
            GL10.GL_TEXTURE_MAG_FILTER, GL10.GL_LINEAR);
    GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES,
            GL10.GL_TEXTURE_WRAP_S, GL10.GL_CLAMP_TO_EDGE);
    GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES,
            GL10.GL_TEXTURE_WRAP_T, GL10.GL_CLAMP_TO_EDGE);

    return texture[0];
  }


  /**
   * Sets the view to our CardboardView and initializes the transformation matrices we will use
   * to render our scene.
   *
   * @param savedInstanceState
   */
  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
      setContentView(R.layout.main_layout);
      setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
      cardboardView = (GvrView) findViewById(R.id.cardboard_view);
      cardboardView.setRenderer(this);
      setGvrView(cardboardView);

    mCamera = new float[16];
    mView = new float[16];
    mOverlayView = (CardboardOverlayView) findViewById(R.id.overlay);
    //mOverlayView.show3DToast("Pull the magnet when you find an object.");
  }

  @Override
  public void onRendererShutdown() {
    Log.i(TAG, "onRendererShutdown");
  }

  @Override
  public void onSurfaceChanged(int width, int height) {
    Log.i(TAG, "onSurfaceChanged");
  }

    /**
     * Creates the buffers we use to store information about the 3D world. OpenGL doesn't use Java
     * arrays, but rather needs data in a format it can understand. Hence we use ByteBuffers.
     *
     * @param eglConfig The EGL configuration used when creating the surface.
     */

    @Override
    public void onSurfaceCreated(EGLConfig eglConfig) {
        Log.i(TAG, "onSurfaceCreated");
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 0.5f); // Dark background so text shows up well

        ByteBuffer bb = ByteBuffer.allocateDirect(squareVertices.length * 4);
        bb.order(ByteOrder.nativeOrder());
        vertexBuffer = bb.asFloatBuffer();
        vertexBuffer.put(squareVertices);
        vertexBuffer.position(0);

        ByteBuffer dlb = ByteBuffer.allocateDirect(drawOrder.length * 2);
        dlb.order(ByteOrder.nativeOrder());
        drawListBuffer = dlb.asShortBuffer();
        drawListBuffer.put(drawOrder);
        drawListBuffer.position(0);

        ByteBuffer bb2 = ByteBuffer.allocateDirect(textureVertices.length * 4);
        bb2.order(ByteOrder.nativeOrder());
        textureVerticesBuffer = bb2.asFloatBuffer();
        textureVerticesBuffer.put(textureVertices);
        textureVerticesBuffer.position(0);

        int vertexShader = MyShaders.loadVertexShader();
        int fragmentShader = MyShaders.loadFragmentShader();

        mProgram = GLES20.glCreateProgram();             // create empty OpenGL ES Program
        GLES20.glAttachShader(mProgram, vertexShader);   // add the vertex shader to program
        GLES20.glAttachShader(mProgram, fragmentShader); // add the fragment shader to program
        GLES20.glLinkProgram(mProgram);

        texture = createTexture();
        startCamera(texture);
    }



  /**
   * Prepares OpenGL ES before we draw a frame.
   *
   * @param headTransform The head transformation in the new frame.
   */
  @Override
  public void onNewFrame(HeadTransform headTransform) {
    float[] mtx = new float[16];
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
    surface.updateTexImage();
    surface.getTransformMatrix(mtx);
  }

    @Override
    public void onDrawEye(Eye eye) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        GLES20.glUseProgram(mProgram);

        GLES20.glActiveTexture(GL_TEXTURE_EXTERNAL_OES);
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, texture);


        mPositionHandle = GLES20.glGetAttribLocation(mProgram, "position");
        GLES20.glEnableVertexAttribArray(mPositionHandle);
        GLES20.glVertexAttribPointer(mPositionHandle, COORDS_PER_VERTEX, GLES20.GL_FLOAT,
                false, vertexStride, vertexBuffer);


        mTextureCoordHandle = GLES20.glGetAttribLocation(mProgram, "inputTextureCoordinate");
        GLES20.glEnableVertexAttribArray(mTextureCoordHandle);
        GLES20.glVertexAttribPointer(mTextureCoordHandle, COORDS_PER_VERTEX, GLES20.GL_FLOAT,
                false, vertexStride, textureVerticesBuffer);

        mColorHandle = GLES20.glGetAttribLocation(mProgram, "s_texture");


        GLES20.glDrawElements(GLES20.GL_TRIANGLES, drawOrder.length,
                GLES20.GL_UNSIGNED_SHORT, drawListBuffer);


        // Disable vertex array
        GLES20.glDisableVertexAttribArray(mPositionHandle);
        GLES20.glDisableVertexAttribArray(mTextureCoordHandle);

        Matrix.multiplyMM(mView, 0, eye.getEyeView(), 0, mCamera, 0);
    }

    @Override
  public void onFrameAvailable(SurfaceTexture arg0) {
  }


  @Override
  public void onFinishFrame(Viewport viewport) {
  }


}
