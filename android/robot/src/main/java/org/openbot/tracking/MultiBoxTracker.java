/* Copyright 2019 The TensorFlow Authors. All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
==============================================================================*/

// Modified by Matthias Mueller - Intel Intelligent Systems Lab - 2020

package org.openbot.tracking;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Cap;
import android.graphics.Paint.Join;
import android.graphics.Paint.Style;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.RectF;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.util.TypedValue;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import org.openbot.env.BorderedText;
import org.openbot.env.ImageUtils;
import org.openbot.env.Logger;
import org.openbot.tflite.Detector.Recognition;
import org.openbot.vehicle.Control;

import timber.log.Timber;


/** Un rastreador que maneja la supresión no máxima y hace coincidir los objetos existentes con nuevas detecciones. */
public class MultiBoxTracker {
  private static final float TEXT_SIZE_DIP = 18;
  //private static final float MIN_SIZE = 16.0f;
  private static final float MIN_SIZE = 4.0f; // en lugar de 16.0f
  private static final int[] COLORS = {
          Color.BLACK,
          Color.RED,
          Color.GREEN,
          Color.YELLOW,
          Color.CYAN,
          Color.MAGENTA,
          Color.WHITE,
          Color.parseColor("#55FF55"),
          Color.parseColor("#FFA500"),
          Color.parseColor("#FF8888"),
          Color.parseColor("#AAAAFF"),
          Color.parseColor("#FFFFAA"),
          Color.parseColor("#55AAAA"),
          Color.parseColor("#AA33AA"),
          Color.parseColor("#0D0068")
  };
  final List<Pair<Float, RectF>> screenRects = new LinkedList<Pair<Float, RectF>>();
  private final Logger logger = new Logger();
  private final Queue<Integer> availableColors = new LinkedList<Integer>();
  private final List<TrackedRecognition> trackedObjects = new LinkedList<TrackedRecognition>();
  private final Paint boxPaint = new Paint();

  //Nueva linea de codigo
  private final Paint centroidPaint = new Paint();
  private final Paint crosshairPaint = new Paint();


  private final float textSizePx;
  private final BorderedText borderedText;
  private Matrix frameToCanvasMatrix;
  private int frameWidth;
  private int frameHeight;
  private int sensorOrientation;
  private float leftControl;
  private float rightControl;
  private boolean useDynamicSpeed = false;

  private float movingCircleX = 0;
  private final float circleY = 350;  // Línea horizontal fija
  private final float circleStep = 1;
  private long lastMoveTime = System.currentTimeMillis();

  // Para control de coordenadas enteras
  private Point currentCirclePoint = new Point(0, (int) circleY);


  public MultiBoxTracker(final Context context) {
    // Inicialización de colores disponibles
    for (final int color : COLORS) {
      availableColors.add(color);
    }

    // Configuración del objeto de pintura
    boxPaint.setColor(Color.RED);
    boxPaint.setStyle(Style.STROKE);
    boxPaint.setStrokeWidth(10.0f);
    boxPaint.setStrokeCap(Cap.ROUND);
    boxPaint.setStrokeJoin(Join.ROUND);
    boxPaint.setStrokeMiter(100);

    centroidPaint.setColor(Color.BLUE);
    centroidPaint.setStyle(Paint.Style.FILL);

    // Configuración de la Mira (Crosshair)
    crosshairPaint.setColor(Color.GREEN);
    crosshairPaint.setStyle(Style.STROKE);
    crosshairPaint.setStrokeWidth(5.0f);

    textSizePx =
            TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, context.getResources().getDisplayMetrics());
    borderedText = new BorderedText(textSizePx);
  }

  //Configuracion del marco
  public synchronized void setFrameConfiguration(final int width, final int height, final int sensorOrientation) {
    frameWidth = width;
    frameHeight = height;
    this.sensorOrientation = sensorOrientation;
  }

  public synchronized void drawDebug(final Canvas canvas) {


    // Configuración del objeto de pintura para texto
    final Paint textPaint = new Paint();
    textPaint.setColor(Color.WHITE);
    textPaint.setTextSize(60.0f);

    final Paint boxPaint = new Paint();
    boxPaint.setColor(Color.RED);
    boxPaint.setAlpha(200);
    boxPaint.setStyle(Style.STROKE);

    for (final Pair<Float, RectF> detection : screenRects) {
      final RectF rect = detection.second;
      canvas.drawRect(rect, boxPaint);
      canvas.drawText("" + detection.first, rect.left, rect.top, textPaint);
      borderedText.drawText(canvas, rect.centerX(), rect.centerY(), "" + detection.first);
    }
  }

  //Resultados de procesamiento de los datos
  public synchronized void trackResults(final List<Recognition> results, final long timestamp) {
    logger.i("Procesando %d resultados de %d", results.size(), timestamp);
    Timber.i("Procesando %d resultados de %d", results.size(), timestamp);
    processResults(results);
  }

  private Matrix getFrameToCanvasMatrix() {
    return frameToCanvasMatrix;
  }

  private void updateFrameToCanvasMatrix(int canvasHeight, int canvasWidth) {
    final boolean rotated = sensorOrientation % 180 == 90;
    final float multiplier =
            Math.min(
                    canvasHeight / (float) (rotated ? frameWidth : frameHeight),
                    canvasWidth / (float) (rotated ? frameHeight : frameWidth));
    frameToCanvasMatrix =
            ImageUtils.getTransformationMatrix(
                    frameWidth,
                    frameHeight,
                    (int) (multiplier * (rotated ? frameHeight : frameWidth)),
                    (int) (multiplier * (rotated ? frameWidth : frameHeight)),
                    sensorOrientation,
                    new RectF(0, 0, 0, 0),
                    false);
  }

  public synchronized Control updateTarget() {
    if (!trackedObjects.isEmpty()) {
      final RectF trackedPos = new RectF(trackedObjects.get(0).location);
      final boolean rotated = sensorOrientation % 180 == 90;
      float imgWidth = (float) (rotated ? frameHeight : frameWidth);
      float boxArea = trackedPos.height() * trackedPos.width();
      float centerX = (rotated ? trackedPos.centerY() : trackedPos.centerX());
      centerX = Math.max(0.0f, Math.min(centerX, imgWidth));
      float x_pos_norm = 1.0f - 2.0f * centerX / imgWidth;
      float x_pos_scaled = rotated ? -x_pos_norm * 1.0f : x_pos_norm * 1.0f;

      if (x_pos_scaled < 0) {
        leftControl = 1.0f;
        rightControl = 1.0f + x_pos_scaled;
      } else {
        leftControl = 1.0f - x_pos_scaled;
        rightControl = 1.0f;
      }

      if (useDynamicSpeed) {
        float scaleFactor = 1.0f - boxArea / (frameWidth * frameHeight);
        scaleFactor = scaleFactor > 0.75f ? 1.0f : scaleFactor;
        if (scaleFactor > 0.25f) {
          leftControl *= scaleFactor;
          rightControl *= scaleFactor;
        } else {
          leftControl = 0.0f;
          rightControl = 0.0f;
        }
      }

    } else {
      leftControl = 0.0f;
      rightControl = 0.0f;
    }

    return new Control(
            (0 > sensorOrientation) ? rightControl : leftControl,
            (0 > sensorOrientation) ? leftControl : rightControl);
  }

  // Dibuja los cuadros de seguimiento en el lienzo

  public synchronized void draw(final Canvas canvas) {
    updateFrameToCanvasMatrix(canvas.getHeight(), canvas.getWidth());

    // 1. Dibujar la Mira (Setpoint) en el centro de la pantalla
    drawCrosshair(canvas);

    for (final TrackedRecognition recognition : trackedObjects) {
      final RectF trackedPos = new RectF(recognition.location);

      getFrameToCanvasMatrix().mapRect(trackedPos);
      boxPaint.setColor(recognition.color);

      float cornerSize = Math.min(trackedPos.width(), trackedPos.height()) / 8.0f;
      canvas.drawRoundRect(trackedPos, cornerSize, cornerSize, boxPaint);

      // Dibuja el centroide del objeto NUEVA LINEA
      float centerX = trackedPos.centerX();
      float centerY = trackedPos.centerY();
      canvas.drawCircle(centerX, centerY, 8.0f, centroidPaint);


      // Dibuja las coordenadas como texto (opcional)
      borderedText.drawText(
              canvas, centerX + 10, centerY - 10,
              String.format(Locale.US, "(%.1f, %.1f)", centerX, centerY), centroidPaint);

      final String labelStringg =
              !TextUtils.isEmpty(recognition.title)
                      ? String.format(Locale.US, "%s %.2f", recognition.title, (100 * recognition.detectionConfidence))
                      : String.format(Locale.US, "%.2f", 100 * recognition.detectionConfidence);
      borderedText.drawText(
              canvas, trackedPos.left + cornerSize, trackedPos.top, labelStringg + "%", boxPaint);


      final String labelString =
              !TextUtils.isEmpty(recognition.title)
                      ? String.format(
                      Locale.US, "%s %.2f", recognition.title, (100 * recognition.detectionConfidence))
                      : String.format(Locale.US, "%.2f", 100 * recognition.detectionConfidence);
      borderedText.drawText(
              canvas, trackedPos.left + cornerSize, trackedPos.top, labelString + "%", boxPaint);
    }
  }

  // --- NUEVA FUNCION: Dibujar la Mira ---
  private void drawCrosshair(Canvas canvas) {
    int centerX = canvas.getWidth() / 2;
    int centerY = canvas.getHeight() / 2;
    int size = 50; // Tamaño de la mira

    // Linea Horizontal
    canvas.drawLine(centerX - size, centerY, centerX + size, centerY, crosshairPaint);
    // Linea Vertical
    canvas.drawLine(centerX, centerY - size, centerX, centerY + size, crosshairPaint);
    // Circulo central
    canvas.drawCircle(centerX, centerY, 20, crosshairPaint);
  }
  // --------------------------------------

  public synchronized void draww(final Canvas canvas) {
    updateFrameToCanvasMatrix(canvas.getHeight(), canvas.getWidth());

    long now = System.currentTimeMillis();
    if (now - lastMoveTime >= 1000) {
      movingCircleX += circleStep;
      if (movingCircleX > canvas.getWidth()) {
        movingCircleX = 0;
      }
      currentCirclePoint.x = Math.round(movingCircleX);
      currentCirclePoint.y = (int) circleY;
      lastMoveTime = now;
    }

    // Dibuja el círculo
    canvas.drawCircle(movingCircleX, circleY, 20.0f, centroidPaint);

    // Dibuja las coordenadas como texto al lado del círculo
    borderedText.drawText(
            canvas,
            movingCircleX + 10,
            circleY - 10,
            String.format(Locale.US, "(%d, %d)", currentCirclePoint.x, currentCirclePoint.y),
            centroidPaint
    );
  }

  // Método para obtener el contorno de los objetos rastreados
  public List<PointF> getContour() {
    List<PointF> contourPoints = new ArrayList<>();
    for (TrackedRecognition recognition : trackedObjects) {
      RectF rect = recognition.location;
      contourPoints.add(new PointF(rect.left, rect.top));
      contourPoints.add(new PointF(rect.right, rect.top));
      contourPoints.add(new PointF(rect.right, rect.bottom));
      contourPoints.add(new PointF(rect.left, rect.bottom));
    }
    return contourPoints;
  }


  public void clearTrackedObjects() {
    trackedObjects.clear();
  }

  // Procesa y almacena los resultados de detección en trackedObjects
  private void processResults(final List<Recognition> results) {
    Log.i("TRACKER", "Recibidos " + results.size() + " resultados del modelo");

    final List<Pair<Float, Recognition>> rectsToTrack = new LinkedList<Pair<Float, Recognition>>();

    screenRects.clear();
    final Matrix rgbFrameToScreen = new Matrix(getFrameToCanvasMatrix());

    for (final Recognition result : results) {
      if (result.getLocation() == null) {
        Log.w("TRACKER", "Detección sin ubicación válida");
        continue;
      }
      final RectF detectionFrameRect = new RectF(result.getLocation());

      final RectF detectionScreenRect = new RectF();
      rgbFrameToScreen.mapRect(detectionScreenRect, detectionFrameRect);

      logger.v(
              "Result! Frame: " + result.getLocation() + " mapped to screen:" + detectionScreenRect);

      screenRects.add(new Pair<Float, RectF>(result.getConfidence(), detectionScreenRect));

      if (detectionFrameRect.width() < MIN_SIZE || detectionFrameRect.height() < MIN_SIZE) {
        logger.w("Degenerate rectangle! " + detectionFrameRect);
        continue;
      }

      rectsToTrack.add(new Pair<Float, Recognition>(result.getConfidence(), result));
    }

    // Limpiar para que los objetos no permanezcan si no se detecta nada.
    trackedObjects.clear();

    if (rectsToTrack.isEmpty()) {
      logger.v("Nothing to track, aborting.");
      return;
    }

    trackedObjects.clear();
    for (final Pair<Float, Recognition> potential : rectsToTrack) {
      final TrackedRecognition trackedRecognition = new TrackedRecognition();
      trackedRecognition.detectionConfidence = potential.first;
      trackedRecognition.location = new RectF(potential.second.getLocation());
      trackedRecognition.title = potential.second.getTitle();
      trackedRecognition.color = COLORS[trackedObjects.size()];
      trackedObjects.add(trackedRecognition);

      if (trackedObjects.size() >= COLORS.length) {
        break;
      }
    }
  }

  public void setDynamicSpeed(boolean isEnabled) {
    useDynamicSpeed = isEnabled;
  }

  //Encontrar el centroide de la imagen detectada NUEVO
  public Point getCenterOfTrackedObject() {
    if (!trackedObjects.isEmpty()) {
      RectF trackedPos = new RectF(trackedObjects.get(0).location);
      getFrameToCanvasMatrix().mapRect(trackedPos);  // aplicar transformación
      int centerX = Math.round(trackedPos.centerX());
      int centerY = Math.round(trackedPos.centerY());
      return new Point(centerX, centerY);
    }
    return null;
  }

  private static class TrackedRecognition {
    RectF location;
    float detectionConfidence;
    int color;
    String title;
  }
}