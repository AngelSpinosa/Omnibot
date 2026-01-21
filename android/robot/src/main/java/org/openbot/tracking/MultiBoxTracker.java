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


/**
 * Clase MultiBoxTracker
 * ---------------------
 * Esta clase se encarga de:
 * 1. Recibir los resultados de detección de la IA (Rectángulos/Bounding Boxes).
 * 2. Dibujar estos rectángulos en la pantalla sobre la vista de cámara.
 * 3. Calcular el centroide (punto central) de los objetos detectados.
 * 4. Dibujar elementos de UI auxiliares como la Mira (Crosshair) y textos de confianza.
 * 5. Mapear coordenadas entre el sistema de la IA (imagen pequeña) y la pantalla del celular.
 */
public class MultiBoxTracker {
  private static final float TEXT_SIZE_DIP = 18;
  //private static final float MIN_SIZE = 16.0f;
  private static final float MIN_SIZE = 4.0f; // Tamaño mínimo de caja para ser considerada válida

  // Lista de colores para diferenciar múltiples objetos detectados
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

  // Pinceles para dibujo
  private final Paint boxPaint = new Paint(); // Para el cuadro rojo
  private final Paint centroidPaint = new Paint(); // Para el punto central azul
  private final Paint crosshairPaint = new Paint(); // Para la mira verde central


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


  /**
   * Constructor.
   * Inicializa los pinceles (Paint) y estilos de dibujo.
   */
  public MultiBoxTracker(final Context context) {
    for (final int color : COLORS) {
      availableColors.add(color);
    }

    // Configuración del pincel para el cuadro (Bounding Box)
    boxPaint.setColor(Color.RED);
    boxPaint.setStyle(Style.STROKE);
    boxPaint.setStrokeWidth(10.0f);
    boxPaint.setStrokeCap(Cap.ROUND);
    boxPaint.setStrokeJoin(Join.ROUND);
    boxPaint.setStrokeMiter(100);

    // Configuración del pincel para el centroide (Punto azul)
    centroidPaint.setColor(Color.BLUE);
    centroidPaint.setStyle(Paint.Style.FILL);

    // Configuración del pincel para la Mira Central (Crosshair verde)
    crosshairPaint.setColor(Color.GREEN);
    crosshairPaint.setStyle(Style.STROKE);
    crosshairPaint.setStrokeWidth(5.0f);

    textSizePx =
            TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, context.getResources().getDisplayMetrics());
    borderedText = new BorderedText(textSizePx);
  }

  /**
   * Configura las dimensiones del frame de la cámara y la orientación del sensor.
   * Necesario para mapear correctamente las coordenadas de la IA a la pantalla.
   */
  public synchronized void setFrameConfiguration(final int width, final int height, final int sensorOrientation) {
    frameWidth = width;
    frameHeight = height;
    this.sensorOrientation = sensorOrientation;
  }

  /**
   * Dibuja información de depuración (rectángulos crudos sin procesar).
   * No se usa en la vista normal de usuario.
   */
  public synchronized void drawDebug(final Canvas canvas) {
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

  /**
   * Recibe los resultados de la IA y los procesa para su visualización.
   * @param results Lista de objetos reconocidos por el detector.
   */
  public synchronized void trackResults(final List<Recognition> results, final long timestamp) {
    logger.i("Procesando %d resultados de %d", results.size(), timestamp);
    Timber.i("Procesando %d resultados de %d", results.size(), timestamp);
    processResults(results);
  }

  private Matrix getFrameToCanvasMatrix() {
    return frameToCanvasMatrix;
  }

  /**
   * Calcula la matriz de transformación para convertir coordenadas de la imagen
   * de entrada (ej. 300x300) a las coordenadas de la pantalla del celular (ej. 1080x1920).
   */
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

  /**
   * Método antiguo para calcular control de ruedas basado en la posición del objeto.
   * Actualmente no se usa para el Pan-Tilt, ya que usamos getCenterOfTrackedObject().
   */
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

  /**
   * Método principal de dibujo. Se llama en cada cuadro de la cámara.
   * 1. Actualiza la matriz de transformación.
   * 2. Dibuja la Mira Central (Referencia para el usuario y el robot).
   * 3. Itera sobre los objetos detectados y dibuja sus cajas y textos.
   */
  public synchronized void draw(final Canvas canvas) {
    updateFrameToCanvasMatrix(canvas.getHeight(), canvas.getWidth());

    // 1. Dibujar la Mira (Setpoint) en el centro de la pantalla
    drawCrosshair(canvas);

    for (final TrackedRecognition recognition : trackedObjects) {
      final RectF trackedPos = new RectF(recognition.location);

      // Mapear el rectángulo de coordenadas IA a coordenadas Pantalla
      getFrameToCanvasMatrix().mapRect(trackedPos);
      boxPaint.setColor(recognition.color);

      // Dibujar el cuadro con esquinas redondeadas
      float cornerSize = Math.min(trackedPos.width(), trackedPos.height()) / 8.0f;
      canvas.drawRoundRect(trackedPos, cornerSize, cornerSize, boxPaint);

      // Dibuja el centroide del objeto (Punto Azul)
      // Este punto es el que el robot intenta alinear con la mira verde
      float centerX = trackedPos.centerX();
      float centerY = trackedPos.centerY();
      canvas.drawCircle(centerX, centerY, 8.0f, centroidPaint);


      // Dibuja las coordenadas X,Y como texto para depuración visual
      borderedText.drawText(
              canvas, centerX + 10, centerY - 10,
              String.format(Locale.US, "(%.1f, %.1f)", centerX, centerY), centroidPaint);

      // Etiqueta con nombre del objeto y % de confianza
      final String labelString =
              !TextUtils.isEmpty(recognition.title)
                      ? String.format(
                      Locale.US, "%s %.2f", recognition.title, (100 * recognition.detectionConfidence))
                      : String.format(Locale.US, "%.2f", 100 * recognition.detectionConfidence);
      borderedText.drawText(
              canvas, trackedPos.left + cornerSize, trackedPos.top, labelString + "%", boxPaint);
    }
  }

  /**
   * Dibuja una cruz verde en el centro exacto del Canvas.
   * Sirve como referencia visual del "Setpoint" (Objetivo) del sistema de control.
   */
  private void drawCrosshair(Canvas canvas) {
    int centerX = canvas.getWidth() / 2;
    int centerY = canvas.getHeight() / 2;
    int size = 50; // Longitud de las líneas de la mira

    // Linea Horizontal
    canvas.drawLine(centerX - size, centerY, centerX + size, centerY, crosshairPaint);
    // Linea Vertical
    canvas.drawLine(centerX, centerY - size, centerX, centerY + size, crosshairPaint);
    // Circulo central pequeño
    canvas.drawCircle(centerX, centerY, 20, crosshairPaint);
  }

  // Método auxiliar antiguo para pruebas de dibujo
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

    canvas.drawCircle(movingCircleX, circleY, 20.0f, centroidPaint);

    borderedText.drawText(
            canvas,
            movingCircleX + 10,
            circleY - 10,
            String.format(Locale.US, "(%d, %d)", currentCirclePoint.x, currentCirclePoint.y),
            centroidPaint
    );
  }

  // Obtiene los puntos de las esquinas de los objetos (para depuración)
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

  /**
   * Procesa la lista de objetos crudos que vienen del detector.
   * Filtra aquellos que tienen ubicación inválida y los almacena en 'trackedObjects'.
   */
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

  /**
   * Obtiene el centro del objeto principal rastreado.
   * IMPORTANTE: Esta es la función clave que usa Vehicle.java para calcular el error PID.
   * Retorna un punto (X, Y) mapeado a las coordenadas de la pantalla actual.
   *
   * @return Point con coordenadas X, Y o null si no hay objetos.
   */
  public Point getCenterOfTrackedObject() {
    if (!trackedObjects.isEmpty()) {
      RectF trackedPos = new RectF(trackedObjects.get(0).location);
      getFrameToCanvasMatrix().mapRect(trackedPos);  // Aplicar transformación a coordenadas de pantalla
      int centerX = Math.round(trackedPos.centerX());
      int centerY = Math.round(trackedPos.centerY());
      return new Point(centerX, centerY);
    }
    return null;
  }

  // Clase interna para almacenar datos de reconocimiento visual
  private static class TrackedRecognition {
    RectF location;
    float detectionConfidence;
    int color;
    String title;
  }
}