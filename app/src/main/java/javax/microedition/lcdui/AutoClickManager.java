/*
 * Copyright 2024 J2ME-Loader-AutoClick Contributors
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
package javax.microedition.lcdui;

import android.os.Handler;
import android.os.HandlerThread;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import javax.microedition.lcdui.event.CanvasEvent;

/**
 * AutoClickManager — injects sequences of synthetic touch (pointer) or keyboard
 * events into the J2ME event queue, simulating automated user input.
 */
public class AutoClickManager {

    public enum ActionType { TAP, KEY }

    public static class AutoAction {
        public ActionType type;
        public int x, y;
        public int keyCode;
        public long holdMs;
        public long delayMs; // Wait time after this specific action

        public static AutoAction tap(int x, int y, long holdMs, long delayMs) {
            AutoAction a = new AutoAction();
            a.type = ActionType.TAP;
            a.x = x;
            a.y = y;
            a.holdMs = holdMs;
            a.delayMs = delayMs;
            return a;
        }

        public static AutoAction key(int keyCode, long holdMs, long delayMs) {
            AutoAction a = new AutoAction();
            a.type = ActionType.KEY;
            a.keyCode = keyCode;
            a.holdMs = holdMs;
            a.delayMs = delayMs;
            return a;
        }
    }

    private static final int POINTER_ID = 0;
    private static final long MIN_DELAY_MS = 10;
    private static final long MIN_HOLD_MS = 10;

    private HandlerThread handlerThread;
    private Handler handler;
    private Canvas target;
    private boolean running;

    private List<AutoAction> sequence = new ArrayList<>();
    private int currentIndex;

    private static final Gson GSON = new Gson();

    public synchronized void startSequence(Canvas canvas, List<AutoAction> actions) {
        stop();
        if (actions == null || actions.isEmpty()) return;
        this.target = canvas;
        this.sequence = new ArrayList<>(actions);
        this.currentIndex = 0;
        
        startThread();
        running = true;
        handler.post(actionRunnable);
    }

    public synchronized void stop() {
        running = false;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        if (handlerThread != null) {
            handlerThread.quitSafely();
            handlerThread = null;
            handler = null;
        }
        target = null;
    }

    public synchronized boolean isRunning() {
        return running;
    }

    public static String toJson(List<AutoAction> sequence) {
        return GSON.toJson(sequence);
    }

    public static List<AutoAction> fromJson(String json) {
        Type type = new TypeToken<ArrayList<AutoAction>>() {}.getType();
        return GSON.fromJson(json, type);
    }

    public static void saveScript(File file, List<AutoAction> sequence) throws IOException {
        try (FileWriter writer = new FileWriter(file)) {
            GSON.toJson(sequence, writer);
        }
    }

    public static List<AutoAction> loadScript(File file) throws IOException {
        try (FileReader reader = new FileReader(file)) {
            Type type = new TypeToken<ArrayList<AutoAction>>() {}.getType();
            return GSON.fromJson(reader, type);
        }
    }

    private void startThread() {
        handlerThread = new HandlerThread("AutoClickThread");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
    }

    private final Runnable actionRunnable = new Runnable() {
        @Override
        public void run() {
            Canvas c;
            AutoAction action;
            synchronized (AutoClickManager.this) {
                if (!running || target == null || sequence.isEmpty()) return;
                c = target;
                action = sequence.get(currentIndex);
                currentIndex = (currentIndex + 1) % sequence.size();
            }

            long hold = Math.max(action.holdMs, MIN_HOLD_MS);
            long delay = Math.max(action.delayMs, MIN_DELAY_MS);

            if (action.type == ActionType.TAP) {
                Display.postEvent(CanvasEvent.getInstance(c,
                        CanvasEvent.POINTER_PRESSED,
                        POINTER_ID,
                        (float) action.x,
                        (float) action.y));

                final Canvas canvas = c;
                final int ax = action.x;
                final int ay = action.y;
                handler.postDelayed(() ->
                        Display.postEvent(CanvasEvent.getInstance(canvas,
                                CanvasEvent.POINTER_RELEASED,
                                POINTER_ID,
                                (float) ax,
                                (float) ay)),
                        hold);
            } else {
                Display.postEvent(CanvasEvent.getInstance(c,
                        CanvasEvent.KEY_PRESSED,
                        action.keyCode));

                final Canvas canvas = c;
                final int akc = action.keyCode;
                handler.postDelayed(() ->
                        Display.postEvent(CanvasEvent.getInstance(canvas,
                                CanvasEvent.KEY_RELEASED,
                                akc)),
                        hold);
            }

            synchronized (AutoClickManager.this) {
                if (running) {
                    handler.postDelayed(this, delay + hold);
                }
            }
        }
    };
}
