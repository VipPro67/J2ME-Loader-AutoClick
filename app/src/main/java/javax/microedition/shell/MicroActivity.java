/*
 * Copyright 2015-2016 Nickolay Savchenko
 * Copyright 2017-2018 Nikita Shakarun
 * Copyright 2019-2022 Yury Kharchenko
 * Copyright 2022-2024 Arman Jussupgaliyev
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

package javax.microedition.shell;

import static ru.playsoftware.j2meloader.util.Constants.*;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.DigitsKeyListener;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.view.MotionEvent;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.AppCompatCheckBox;
import androidx.preference.PreferenceManager;

import org.acra.ACRA;
import org.acra.ErrorReporter;

import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import android.graphics.Color;
import android.text.Html;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Objects;

import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.AutoClickManager;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.List;
import javax.microedition.lcdui.ViewHandler;
import javax.microedition.lcdui.event.SimpleEvent;
import javax.microedition.lcdui.keyboard.VirtualKeyboard;
import javax.microedition.location.LocationProviderImpl;
import javax.microedition.util.ContextHolder;

import io.reactivex.SingleObserver;
import io.reactivex.disposables.Disposable;
import ru.playsoftware.j2meloader.BuildConfig;
import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.databinding.ActivityMicroBinding;
import ru.playsoftware.j2meloader.util.Constants;
import ru.playsoftware.j2meloader.util.LogUtils;

public class MicroActivity extends AppCompatActivity {
	private static final int ORIENTATION_DEFAULT = 0;
	private static final int ORIENTATION_AUTO = 1;
	private static final int ORIENTATION_PORTRAIT = 2;
	private static final int ORIENTATION_LANDSCAPE = 3;

	private Displayable current;
	private boolean visible;
	private boolean actionBarEnabled;
	private boolean statusBarEnabled;
	private MicroLoader microLoader;
	private String appName;
	private InputMethodManager inputMethodManager;
	private int menuKey;
	private String appPath;
	private final AutoClickManager autoClickManager = new AutoClickManager();
	private java.util.List<AutoClickManager.AutoAction> lastAutoClickSequence = null;

	private boolean isRecordingAutoClick = false;
	private java.util.List<AutoClickManager.AutoAction> recordedSequence = null;
	private long lastAutoClickEventTime = 0;
	private AutoClickManager.AutoAction pendingAutoClickAction = null;
	private View stopRecordingOverlay = null;

	public void onAutoClickEventPressed(int type, int x, int y, int keyCode) {
		if (!isRecordingAutoClick) return;
		long now = System.currentTimeMillis();
		long delay = now - lastAutoClickEventTime;
		if (recordedSequence != null && !recordedSequence.isEmpty()) {
			recordedSequence.get(recordedSequence.size() - 1).delayMs = Math.max(10, delay);
		}
		if (pendingAutoClickAction != null) {
			pendingAutoClickAction.holdMs = Math.max(10, delay);
			recordedSequence.add(pendingAutoClickAction);
		}
		pendingAutoClickAction = new AutoClickManager.AutoAction();
		if (type == 0) {
			pendingAutoClickAction.type = AutoClickManager.ActionType.TAP;
			pendingAutoClickAction.x = x;
			pendingAutoClickAction.y = y;
		} else {
			pendingAutoClickAction.type = AutoClickManager.ActionType.KEY;
			pendingAutoClickAction.keyCode = keyCode;
		}
		pendingAutoClickAction.delayMs = 10;
		lastAutoClickEventTime = now;
	}

	public void onAutoClickEventReleased(int type, int x, int y, int keyCode) {
		if (!isRecordingAutoClick || pendingAutoClickAction == null) return;
		long now = System.currentTimeMillis();
		pendingAutoClickAction.holdMs = Math.max(10, now - lastAutoClickEventTime);
		recordedSequence.add(pendingAutoClickAction);
		pendingAutoClickAction = null;
		lastAutoClickEventTime = now;
	}

	private void startAutoClickRecording() {
		isRecordingAutoClick = true;
		recordedSequence = new java.util.ArrayList<>();
		lastAutoClickEventTime = System.currentTimeMillis();
		pendingAutoClickAction = null;

		Toast.makeText(this, R.string.auto_click_record_start, Toast.LENGTH_SHORT).show();

		Button btn = new Button(this);
		btn.setText(R.string.auto_click_stop_record);
		btn.setBackgroundColor(0xAAFF0000);
		btn.setTextColor(Color.WHITE);
		stopRecordingOverlay = btn;
		
		FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2);
		lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
		lp.topMargin = (int) (50 * getResources().getDisplayMetrics().density);
		
		btn.setOnClickListener(v -> stopAutoClickRecording());
		binding.displayableContainer.addView(stopRecordingOverlay, lp);
	}

	private void stopAutoClickRecording() {
		isRecordingAutoClick = false;
		if (stopRecordingOverlay != null) {
			binding.displayableContainer.removeView(stopRecordingOverlay);
			stopRecordingOverlay = null;
		}
		if (pendingAutoClickAction != null) {
			pendingAutoClickAction.holdMs = Math.max(10, System.currentTimeMillis() - lastAutoClickEventTime);
			recordedSequence.add(pendingAutoClickAction);
			pendingAutoClickAction = null;
		}
		lastAutoClickSequence = recordedSequence;
		showAutoClickDialog(lastAutoClickSequence);
	}

	private static class ActionRow {
		LinearLayout view;
		RadioButton rbTap, rbKey;
		EditText etX, etY, etKeyCode, etHold, etDelay;
		Button btnRemove, btnPick;

		ActionRow(android.content.Context ctx, float dp, Runnable onPick) {
			int p = (int) (8 * dp);
			int sp = (int) (4 * dp);
			view = new LinearLayout(ctx);
			view.setOrientation(LinearLayout.VERTICAL);
			view.setBackgroundResource(android.R.drawable.dialog_holo_light_frame);
			view.setPadding(p, p, p, p);
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
			lp.setMargins(0, 0, 0, (int) (12 * dp));
			view.setLayoutParams(lp);

			// --- Row 1: Mode & Remove ---
			LinearLayout row1 = new LinearLayout(ctx);
			row1.setGravity(Gravity.CENTER_VERTICAL);
			RadioGroup group = new RadioGroup(ctx);
			group.setOrientation(RadioGroup.HORIZONTAL);
			rbTap = new RadioButton(ctx);
			rbTap.setText(R.string.auto_click_tap);
			rbTap.setId(View.generateViewId());
			rbKey = new RadioButton(ctx);
			rbKey.setText(R.string.auto_click_key);
			rbKey.setId(View.generateViewId());
			group.addView(rbTap);
			group.addView(rbKey);
			rbTap.setChecked(true);
			row1.addView(group, new LinearLayout.LayoutParams(0, -2, 1));

			btnRemove = new Button(ctx, null, android.R.attr.buttonStyleSmall);
			btnRemove.setText(R.string.auto_click_remove_action);
			btnRemove.setTextColor(0xFFFF4444);
			row1.addView(btnRemove);
			view.addView(row1);

			// --- Row 2: Inputs (X/Y or KeyCode) ---
			LinearLayout row2 = new LinearLayout(ctx);
			row2.setOrientation(LinearLayout.HORIZONTAL);
			row2.setGravity(Gravity.BOTTOM);

			// TAP segment
			LinearLayout segTap = new LinearLayout(ctx);
			segTap.setOrientation(LinearLayout.HORIZONTAL);
			segTap.setGravity(Gravity.BOTTOM);
			etX = makeField(ctx, "X", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED, "50");
			etY = makeField(ctx, "Y", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED, "50");
			segTap.addView(etX, new LinearLayout.LayoutParams(0, -2, 1));
			segTap.addView(etY, new LinearLayout.LayoutParams(0, -2, 1));
			btnPick = new Button(ctx, null, android.R.attr.buttonStyleSmall);
			btnPick.setText(R.string.auto_click_pick);
			segTap.addView(btnPick);
			row2.addView(segTap, new LinearLayout.LayoutParams(0, -2, 1));

			// KEY segment
			etKeyCode = makeField(ctx, "Key Code", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED, "-5");
			etKeyCode.setVisibility(View.GONE);
			row2.addView(etKeyCode, new LinearLayout.LayoutParams(0, -2, 1));
			view.addView(row2);

			// --- Row 3: Timing (Hold & Delay) ---
			LinearLayout row3 = new LinearLayout(ctx);
			etHold = makeField(ctx, "Hold (ms)", InputType.TYPE_CLASS_NUMBER, "50");
			etDelay = makeField(ctx, "Delay (ms)", InputType.TYPE_CLASS_NUMBER, "500");
			row3.addView(etHold, new LinearLayout.LayoutParams(0, -2, 1));
			row3.addView(etDelay, new LinearLayout.LayoutParams(0, -2, 1));
			view.addView(row3);

			group.setOnCheckedChangeListener((g, id) -> {
				boolean tap = id == rbTap.getId();
				segTap.setVisibility(tap ? View.VISIBLE : View.GONE);
				etKeyCode.setVisibility(tap ? View.GONE : View.VISIBLE);
			});

			btnPick.setOnClickListener(v -> onPick.run());
		}

		private static EditText makeField(android.content.Context ctx, String label, int type, String def) {
			LinearLayout container = new LinearLayout(ctx);
			container.setOrientation(LinearLayout.VERTICAL);
			TextView tv = new TextView(ctx);
			tv.setText(label);
			tv.setTextSize(10);
			container.addView(tv);
			EditText et = new EditText(ctx);
			et.setInputType(type);
			et.setText(def);
			et.setTextSize(14);
			et.setSelectAllOnFocus(true);
			container.addView(et);
			// We return the EditText but it's wrapped in a container. 
			// Wait, if we return the ET, we lose the container reference for adding to parent.
			// Let's return the container and expose the ET.
			return et; 
		}

		// Since our makeField returns ET, we need to wrap it ourselves in the constructor rows
		// to include the tiny labels. Let's fix makeField to be a helper that Adds to a parent instead.
		private static EditText addLabeledField(LinearLayout parent, String label, int type, String def) {
			android.content.Context ctx = parent.getContext();
			LinearLayout container = new LinearLayout(ctx);
			container.setOrientation(LinearLayout.VERTICAL);
			TextView tv = new TextView(ctx);
			tv.setText(label);
			tv.setTextSize(10);
			tv.setTextColor(Color.WHITE);
			tv.setPadding((int)(4*ctx.getResources().getDisplayMetrics().density), 0, 0, 0);
			container.addView(tv);
			EditText et = new EditText(ctx);
			et.setInputType(type);
			et.setText(def);
			et.setTextSize(14);
			et.setTextColor(Color.WHITE);
			et.setHintTextColor(0x88FFFFFF);
			et.setPadding((int)(4*ctx.getResources().getDisplayMetrics().density), 0, (int)(4*ctx.getResources().getDisplayMetrics().density), 0);
			container.addView(et);
			parent.addView(container, new LinearLayout.LayoutParams(0, -2, 1));
			return et;
		}

		// Re-wrapping the constructor logic to use addLabeledField
		static ActionRow create(android.content.Context ctx, float dp, Runnable onPick) {
			ActionRow ar = new ActionRow();
			int p = (int) (8 * dp);
			ar.view = new LinearLayout(ctx);
			ar.view.setOrientation(LinearLayout.VERTICAL);
			ar.view.setBackgroundResource(android.R.drawable.dialog_holo_dark_frame);
			ar.view.setPadding(p, p, p, p);
			ar.view.setAlpha(0.9f);
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
			lp.setMargins(0, 0, 0, (int) (12 * dp));
			ar.view.setLayoutParams(lp);

			LinearLayout r1 = new LinearLayout(ctx);
			r1.setGravity(Gravity.CENTER_VERTICAL);
			RadioGroup g = new RadioGroup(ctx); g.setOrientation(RadioGroup.HORIZONTAL);
			ar.rbTap = new RadioButton(ctx); ar.rbTap.setText(R.string.auto_click_tap); ar.rbTap.setId(View.generateViewId());
			ar.rbTap.setTextColor(Color.WHITE);
			ar.rbKey = new RadioButton(ctx); ar.rbKey.setText(R.string.auto_click_key); ar.rbKey.setId(View.generateViewId());
			ar.rbKey.setTextColor(Color.WHITE);
			g.addView(ar.rbTap); g.addView(ar.rbKey); ar.rbTap.setChecked(true);
			r1.addView(g, new LinearLayout.LayoutParams(0, -2, 1));
			ar.btnRemove = new Button(ctx, null, android.R.attr.buttonStyleSmall);
			ar.btnRemove.setText(R.string.auto_click_remove_action); ar.btnRemove.setTextColor(0xFFFF4444);
			r1.addView(ar.btnRemove); ar.view.addView(r1);

			LinearLayout r2 = new LinearLayout(ctx);
			LinearLayout sTap = new LinearLayout(ctx); sTap.setGravity(Gravity.BOTTOM);
			ar.etX = addLabeledField(sTap, "X", InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_SIGNED, "50");
			ar.etY = addLabeledField(sTap, "Y", InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_SIGNED, "50");
			ar.btnPick = new Button(ctx, null, android.R.attr.buttonStyleSmall); 
			ar.btnPick.setText(R.string.auto_click_pick);
			ar.btnPick.setTextColor(Color.WHITE);
			sTap.addView(ar.btnPick);
			r2.addView(sTap, new LinearLayout.LayoutParams(0, -2, 1));
			ar.etKeyCode = addLabeledField(r2, "Key Code", InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_SIGNED, "-5");
			((View) ar.etKeyCode.getParent()).setVisibility(View.GONE); // Hide the container
			ar.view.addView(r2);

			LinearLayout r3 = new LinearLayout(ctx);
			ar.etHold = addLabeledField(r3, "Hold (ms)", InputType.TYPE_CLASS_NUMBER, "50");
			ar.etDelay = addLabeledField(r3, "Delay (ms)", InputType.TYPE_CLASS_NUMBER, "500");
			ar.view.addView(r3);

			g.setOnCheckedChangeListener((group, id) -> {
				boolean tap = id == ar.rbTap.getId();
				sTap.setVisibility(tap ? View.VISIBLE : View.GONE);
				((View) ar.etKeyCode.getParent()).setVisibility(tap ? View.GONE : View.VISIBLE);
			});
			ar.btnPick.setOnClickListener(v -> onPick.run());
			return ar;
		}

		private ActionRow() {} // Private for the factory

		AutoClickManager.AutoAction toAutoAction() {
			long hold = parseLongSafe(etHold.getText().toString(), 50);
			long delay = parseLongSafe(etDelay.getText().toString(), 500);
			if (rbTap.isChecked()) {
				int x = parseIntSafe(etX.getText().toString(), 50);
				int y = parseIntSafe(etY.getText().toString(), 50);
				return AutoClickManager.AutoAction.tap(x, y, hold, delay);
			} else {
				int kc = parseIntSafe(etKeyCode.getText().toString(), -5);
				return AutoClickManager.AutoAction.key(kc, hold, delay);
			}
		}

		void fromAutoAction(AutoClickManager.AutoAction action) {
			if (action.type == AutoClickManager.ActionType.TAP) {
				rbTap.setChecked(true);
				etX.setText(String.valueOf(action.x));
				etY.setText(String.valueOf(action.y));
			} else {
				rbKey.setChecked(true);
				etKeyCode.setText(String.valueOf(action.keyCode));
			}
			etHold.setText(String.valueOf(action.holdMs));
			etDelay.setText(String.valueOf(action.delayMs));
		}
	}

	public ActivityMicroBinding binding;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		lockNightMode();
		super.onCreate(savedInstanceState);
		ContextHolder.setCurrentActivity(this);

		binding = ActivityMicroBinding.inflate(getLayoutInflater());
		View view = binding.getRoot();
		setContentView(view);
		setSupportActionBar(binding.toolbar);

		SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
		actionBarEnabled = sp.getBoolean(PREF_TOOLBAR, false);
		statusBarEnabled = sp.getBoolean(PREF_STATUSBAR, false);
		if (sp.getBoolean(PREF_ADD_CUTOUT_AREA, false) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			getWindow().getAttributes().layoutInDisplayCutoutMode =
					WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
		}
		if (sp.getBoolean(PREF_KEEP_SCREEN, false)) {
			getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		}
		ContextHolder.setVibration(sp.getBoolean(PREF_VIBRATION, true));
		Canvas.setScreenshotRawMode(sp.getBoolean(PREF_SCREENSHOT_SWITCH, false));
		Intent intent = getIntent();
		if (BuildConfig.FULL_EMULATOR) {
			appName = intent.getStringExtra(KEY_MIDLET_NAME);
			Uri data = intent.getData();
			if (data == null) {
				showErrorDialog("Invalid intent: app path is null");
				return;
			}
			appPath = data.toString();
		} else {
			appName = getTitle().toString();
			appPath = getApplicationInfo().dataDir + "/files/converted/midlet";
			File dir = new File(appPath);
			if (!dir.exists() && !dir.mkdirs()) {
				throw new RuntimeException("Can't access file system");
			}
		}
		String arguments = intent.getStringExtra(KEY_START_ARGUMENTS);
		if (arguments != null) {
			MidletSystem.setProperty("com.nokia.mid.cmdline", arguments);
			String[] arr = arguments.split(";");
			for (String s: arr) {
				if (s.length() == 0) {
					continue;
				}
				if (s.contains("=")) {
					int i = s.indexOf('=');
					String k = s.substring(0, i);
					String v = s.substring(i + 1);
					MidletSystem.setProperty(k, v);
				} else {
					MidletSystem.setProperty(s, "");
				}
			}
		}
		MidletSystem.setProperty("com.nokia.mid.cmdline.instance", "1");
		microLoader = new MicroLoader(this, appPath);
		if (!microLoader.init()) {
			Config.startApp(this, appName, appPath, true, arguments);
			finish();
			return;
		}
		microLoader.applyConfiguration();
		VirtualKeyboard vk = ContextHolder.getVk();
		int orientation = microLoader.getOrientation();
		if (vk != null) {
			vk.setView(binding.overlayView);
			binding.overlayView.addLayer(vk);
			if (vk.isPhone()) {
				orientation = ORIENTATION_PORTRAIT;
			}
		}
		setOrientation(orientation);
		menuKey = microLoader.getMenuKeyCode();
		inputMethodManager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);

		try {
			loadMIDlet();
		} catch (Exception e) {
			e.printStackTrace();
			showErrorDialog(e.toString());
		}
	}

	public void lockNightMode() {
		int current = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
		if (current == Configuration.UI_MODE_NIGHT_YES) {
			AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
		} else {
			AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
		}
	}

	@Override
	public void onResume() {
		super.onResume();
		visible = true;
		MidletThread.resumeApp();
	}

	@Override
	public void onPause() {
		visible = false;
		hideSoftInput();
		autoClickManager.stop();
		MidletThread.pauseApp();
		super.onPause();
	}

	private void hideSoftInput() {
		if (inputMethodManager != null) {
			IBinder windowToken = binding.displayableContainer.getWindowToken();
			inputMethodManager.hideSoftInputFromWindow(windowToken, 0);
		}
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		if (hasFocus && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT &&
				current instanceof Canvas) {
			hideSystemUI();
		}
	}

	@SuppressLint("SourceLockedOrientationActivity")
	private void setOrientation(int orientation) {
		switch (orientation) {
			case ORIENTATION_AUTO:
				setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
				break;
			case ORIENTATION_PORTRAIT:
				setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
				break;
			case ORIENTATION_LANDSCAPE:
				setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
				break;
			case ORIENTATION_DEFAULT:
			default:
				setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
				break;
		}
	}

	private void loadMIDlet() throws Exception {
		LinkedHashMap<String, String> midlets = microLoader.loadMIDletList();
		int size = midlets.size();
		String[] midletsNameArray = midlets.values().toArray(new String[0]);
		String[] midletsClassArray = midlets.keySet().toArray(new String[0]);
		if (size == 0) {
			throw new Exception("No MIDlets found");
		} else if (size == 1) {
			MidletThread.create(microLoader, midletsClassArray[0]);
		} else {
			showMidletDialog(midletsNameArray, midletsClassArray);
		}
	}

	private void showMidletDialog(String[] names, final String[] classes) {
		AlertDialog.Builder builder = new AlertDialog.Builder(this)
				.setTitle(R.string.select_dialog_title)
				.setItems(names, (d, n) -> {
					String clazz = classes[n];
					ErrorReporter errorReporter = ACRA.getErrorReporter();
					String report = errorReporter.getCustomData(Constants.KEY_APPCENTER_ATTACHMENT);
					StringBuilder sb = new StringBuilder();
					if (report != null) {
						sb.append(report).append("\n");
					}
					sb.append("Begin app: ").append(names[n]).append(", ").append(clazz);
					errorReporter.putCustomData(Constants.KEY_APPCENTER_ATTACHMENT, sb.toString());
					MidletThread.create(microLoader, clazz);
					MidletThread.resumeApp();
				})
				.setOnCancelListener(d -> {
					d.dismiss();
					MidletThread.notifyDestroyed();
				});
		builder.show();
	}

	void showErrorDialog(String message) {
		AlertDialog.Builder builder = new AlertDialog.Builder(this)
				.setIcon(android.R.drawable.ic_dialog_alert)
				.setTitle(R.string.error)
				.setMessage(message)
				.setPositiveButton(android.R.string.ok, (d, w) -> MidletThread.notifyDestroyed());
		builder.setOnCancelListener(dialogInterface -> MidletThread.notifyDestroyed());
		builder.show();
	}

	private int getToolBarHeight() {
		int[] attrs = new int[]{androidx.appcompat.R.attr.actionBarSize};
		TypedArray ta = obtainStyledAttributes(attrs);
		int toolBarHeight = ta.getDimensionPixelSize(0, -1);
		ta.recycle();
		return toolBarHeight;
	}

	private void hideSystemUI() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
			int flags = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
			if (!statusBarEnabled) {
				flags |= View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
						| View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_FULLSCREEN;
			}
			getWindow().getDecorView().setSystemUiVisibility(flags);
		} else if (!statusBarEnabled) {
			getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
					WindowManager.LayoutParams.FLAG_FULLSCREEN);
		}
	}

	private void showSystemUI() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
			getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
		} else {
			getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
		}
	}

	public void setCurrent(Displayable displayable) {
		ViewHandler.postEvent(new SetCurrentEvent(current, displayable));
		current = displayable;
	}

	public Displayable getCurrent() {
		return current;
	}

	public boolean isVisible() {
		return visible;
	}

	public void showExitConfirmation() {
		AlertDialog.Builder alertBuilder = new AlertDialog.Builder(this);
		alertBuilder.setTitle(R.string.CONFIRMATION_REQUIRED)
				.setMessage(R.string.FORCE_CLOSE_CONFIRMATION)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					hideSoftInput();
					MidletThread.destroyApp();
				})
				.setNeutralButton(R.string.action_settings, (d, w) -> {
					hideSoftInput();
					Config.startApp(this, appName, appPath, true);
					MidletThread.destroyApp();
				})
				.setNegativeButton(android.R.string.cancel, null);
		alertBuilder.create().show();
	}

	@Override
	public boolean dispatchKeyEvent(KeyEvent event) {
		if (event.getKeyCode() == KeyEvent.KEYCODE_MENU)
			if (current instanceof Canvas && binding.displayableContainer.dispatchKeyEvent(event)) {
				return true;
			} else if (event.getAction() == KeyEvent.ACTION_DOWN) {
				if (event.getRepeatCount() == 0) {
					event.startTracking();
					return true;
				} else if (event.isLongPress()) {
					return onKeyLongPress(event.getKeyCode(), event);
				}
			} else if (event.getAction() == KeyEvent.ACTION_UP) {
				return onKeyUp(event.getKeyCode(), event);
			}
		return super.dispatchKeyEvent(event);
	}

	@Override
	public void openOptionsMenu() {
		if (!actionBarEnabled &&
				Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && current instanceof Canvas) {
			showSystemUI();
		}
		super.openOptionsMenu();
	}

	@Override
	public boolean onKeyLongPress(int keyCode, KeyEvent event) {
		if (keyCode == menuKey || keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU) {
			showExitConfirmation();
			return true;
		}
		return super.onKeyLongPress(keyCode, event);
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if (keyCode == KeyEvent.KEYCODE_MENU) {
			return false;
		}
		return super.onKeyDown(keyCode, event);
	}

	@Override
	public boolean onKeyUp(int keyCode, KeyEvent event) {
		if ((keyCode == menuKey || keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU)
				&& (event.getFlags() & (KeyEvent.FLAG_LONG_PRESS | KeyEvent.FLAG_CANCELED)) == 0) {
			openOptionsMenu();
			return true;
		}
		return super.onKeyUp(keyCode, event);
	}

	@Override
	public void onBackPressed() {
		// Intentionally overridden by empty due to support for back-key remapping.
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.midlet_displayable, menu);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
			menu.findItem(R.id.action_lock_orientation).setVisible(true);
		}
		if (actionBarEnabled) {
			menu.findItem(R.id.action_ime_keyboard).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
			menu.findItem(R.id.action_take_screenshot).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
		}
		if (inputMethodManager == null) {
			menu.findItem(R.id.action_ime_keyboard).setVisible(false);
		}
		if (ContextHolder.getVk() == null) {
			menu.findItem(R.id.action_submenu_vk).setVisible(false);
		}
		return true;
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		if (current instanceof Canvas) {
			menu.setGroupVisible(R.id.action_group_canvas, true);
			VirtualKeyboard vk = ContextHolder.getVk();
			if (vk != null) {
				boolean visible = vk.getLayoutEditMode() != VirtualKeyboard.LAYOUT_EOF;
				menu.findItem(R.id.action_layout_edit_finish).setVisible(visible);
			}
		} else {
			menu.setGroupVisible(R.id.action_group_canvas, false);
		}
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(@NonNull MenuItem item) {
		int id = item.getItemId();
		if (id == R.id.action_exit_midlet) {
			showExitConfirmation();
		} else if (id == R.id.action_save_log) {
			saveLog();
		} else if (id == R.id.action_lock_orientation) {
			if (item.isChecked()) {
				VirtualKeyboard vk = ContextHolder.getVk();
				int orientation = vk != null && vk.isPhone() ? ORIENTATION_PORTRAIT : microLoader.getOrientation();
				setOrientation(orientation);
				item.setChecked(false);
			} else {
				item.setChecked(true);
				setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
			}
		} else if (id == R.id.action_ime_keyboard) {
			inputMethodManager.toggleSoftInputFromWindow(binding.displayableContainer.getWindowToken(),
					InputMethodManager.SHOW_FORCED, 0);
		} else if (id == R.id.action_take_screenshot) {
			takeScreenshot();
		} else if (id == R.id.action_limit_fps) {
			showLimitFpsDialog();
		} else if (id == R.id.action_auto_click) {
			showAutoClickDialog();
		} else if (ContextHolder.getVk() != null) {
			// Handled only when virtual keyboard is enabled
			handleVkOptions(id);
		}
		return true;
	}

	private void handleVkOptions(int id) {
		VirtualKeyboard vk = ContextHolder.getVk();
		if (id == R.id.action_layout_edit_mode) {
			vk.setLayoutEditMode(VirtualKeyboard.LAYOUT_KEYS);
			Toast.makeText(this, R.string.layout_edit_mode, Toast.LENGTH_SHORT).show();
		} else if (id == R.id.action_layout_scale_mode) {
			vk.setLayoutEditMode(VirtualKeyboard.LAYOUT_SCALES);
			Toast.makeText(this, R.string.layout_scale_mode, Toast.LENGTH_SHORT).show();
		} else if (id == R.id.action_layout_edit_finish) {
			vk.setLayoutEditMode(VirtualKeyboard.LAYOUT_EOF);
			Toast.makeText(this, R.string.layout_edit_finished, Toast.LENGTH_SHORT).show();
			showSaveVkAlert(false);
		} else if (id == R.id.action_layout_switch) {
			showSetLayoutDialog();
		} else if (id == R.id.action_hide_buttons) {
			showHideButtonDialog();
		}
	}

	@SuppressLint("CheckResult")
	private void takeScreenshot() {
		microLoader.takeScreenshot((Canvas) current, new SingleObserver<String>() {
			@Override
			public void onSubscribe(@NonNull Disposable d) {
			}

			@Override
			public void onSuccess(@NonNull String s) {
				Toast.makeText(MicroActivity.this, getString(R.string.screenshot_saved)
						+ " " + s, Toast.LENGTH_LONG).show();
			}

			@Override
			public void onError(@NonNull Throwable e) {
				e.printStackTrace();
				Toast.makeText(MicroActivity.this, R.string.error, Toast.LENGTH_SHORT).show();
			}
		});
	}

	private void saveLog() {
		try {
			LogUtils.writeLog();
			Toast.makeText(this, R.string.log_saved, Toast.LENGTH_SHORT).show();
		} catch (IOException e) {
			e.printStackTrace();
			Toast.makeText(this, R.string.error, Toast.LENGTH_SHORT).show();
		}
	}

	private void showHideButtonDialog() {
		final VirtualKeyboard vk = ContextHolder.getVk();
		boolean[] states = vk.getKeysVisibility();
		boolean[] changed = states.clone();
		new AlertDialog.Builder(this)
				.setTitle(R.string.hide_buttons)
				.setMultiChoiceItems(vk.getKeyNames(), changed, (dialog, which, isChecked) -> {})
				.setPositiveButton(android.R.string.ok, (dialog, which) -> {
					if (!Arrays.equals(states, changed)) {
						vk.setKeysVisibility(changed);
						showSaveVkAlert(true);
					}
				}).show();
	}

	private void showSaveVkAlert(boolean keepScreenPreferred) {
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setTitle(R.string.CONFIRMATION_REQUIRED);
		builder.setMessage(R.string.pref_vk_save_alert);
		builder.setNegativeButton(android.R.string.no, null);
		AlertDialog dialog = builder.create();

		final VirtualKeyboard vk = ContextHolder.getVk();
		if (vk.isPhone()) {
			AppCompatCheckBox cb = new AppCompatCheckBox(this);
			cb.setText(R.string.opt_save_screen_params);
			cb.setChecked(keepScreenPreferred);

			TypedValue out = new TypedValue();
			getTheme().resolveAttribute(androidx.appcompat.R.attr.dialogPreferredPadding, out, true);
			int paddingH = getResources().getDimensionPixelOffset(out.resourceId);
			int paddingT = getResources().getDimensionPixelOffset(androidx.appcompat.R.dimen.abc_dialog_padding_top_material);
			dialog.setView(cb, paddingH, paddingT, paddingH, 0);

			dialog.setButton(dialog.BUTTON_POSITIVE, getText(android.R.string.yes), (d, w) -> {
				if (cb.isChecked()) {
					vk.saveScreenParams();
				}
				vk.onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM);
			});
		} else {
			dialog.setButton(dialog.BUTTON_POSITIVE, getText(android.R.string.yes), (d, w) ->
					ContextHolder.getVk().onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM));
		}
		dialog.show();
	}

	private void showSetLayoutDialog() {
		final VirtualKeyboard vk = ContextHolder.getVk();
		AlertDialog.Builder builder = new AlertDialog.Builder(this)
				.setTitle(R.string.layout_switch)
				.setSingleChoiceItems(R.array.PREF_VK_TYPE_ENTRIES, vk.getLayout(), null)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					vk.setLayout(((AlertDialog) d).getListView().getCheckedItemPosition());
					if (vk.isPhone()) {
						setOrientation(ORIENTATION_PORTRAIT);
					} else {
						setOrientation(microLoader.getOrientation());
					}
				});
		builder.show();
	}

	private void showLimitFpsDialog() {
		EditText editText = new EditText(this);
		editText.setHint(R.string.unlimited);
		editText.setInputType(InputType.TYPE_CLASS_NUMBER);
		editText.setKeyListener(DigitsKeyListener.getInstance("0123456789"));
		editText.setMaxLines(1);
		editText.setSingleLine(true);
		float density = getResources().getDisplayMetrics().density;
		LinearLayout linearLayout = new LinearLayout(this);
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		int margin = (int) (density * 20);
		params.setMargins(margin, 0, margin, 0);
		linearLayout.addView(editText, params);
		int paddingVertical = (int) (density * 16);
		int paddingHorizontal = (int) (density * 8);
		editText.setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical);
		new AlertDialog.Builder(this)
				.setTitle(R.string.PREF_LIMIT_FPS)
				.setView(linearLayout)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					Editable text = editText.getText();
					int fps = 0;
					try {
						fps = TextUtils.isEmpty(text) ? 0 : Integer.parseInt(text.toString().trim());
					} catch (NumberFormatException ignored) {
					}
					microLoader.setLimitFps(fps);
				})
				.setNegativeButton(android.R.string.cancel, null)
				.setNeutralButton(R.string.reset, ((d, which) -> microLoader.setLimitFps(-1)))
				.show();
	}

	private void showAutoClickDialog() {
		showAutoClickDialog(lastAutoClickSequence);
	}

	/**
	 * Shows the Auto-Click configuration dialog.
	 * Supports multiple actions, script persistence, and screen point picking.
	 */
	private void showAutoClickDialog(java.util.List<AutoClickManager.AutoAction> initialSequence) {
		if (!(current instanceof Canvas)) return;
		final Canvas canvas = (Canvas) current;
		float dp = getResources().getDisplayMetrics().density;
		int pad = (int) (16 * dp);
		final AlertDialog[] dialog = {null};

		ScrollView scroll = new ScrollView(this);
		LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setPadding(pad, pad, pad, pad);
		scroll.addView(root);

		// Header row: Title + Save/Load
		LinearLayout header = new LinearLayout(this);
		header.setOrientation(LinearLayout.HORIZONTAL);
		header.setGravity(Gravity.CENTER_VERTICAL);
		Button btnHelp = new Button(this, null, android.R.attr.buttonStyleSmall);
		btnHelp.setText("?");
		btnHelp.setTextColor(Color.CYAN);
		btnHelp.setOnClickListener(v -> {
			new AlertDialog.Builder(this)
					.setTitle(R.string.auto_click_help)
					.setMessage(Html.fromHtml(getString(R.string.auto_click_help_text)))
					.setPositiveButton(android.R.string.ok, null)
					.show();
		});
		header.addView(btnHelp);

		Button btnRecord = new Button(this, null, android.R.attr.buttonStyleSmall);
		btnRecord.setText(R.string.auto_click_record);
		btnRecord.setTextColor(Color.WHITE);
		header.addView(btnRecord);
		btnRecord.setOnClickListener(v -> {
			if (dialog[0] != null) dialog[0].dismiss();
			startAutoClickRecording();
		});

		Button btnLoad = new Button(this, null, android.R.attr.buttonStyleSmall);
		btnLoad.setText(R.string.auto_click_load);
		btnLoad.setTextColor(Color.WHITE);
		header.addView(btnLoad);

		Button btnSave = new Button(this, null, android.R.attr.buttonStyleSmall);
		btnSave.setText(R.string.auto_click_save);
		btnSave.setTextColor(Color.WHITE);
		header.addView(btnSave);
		root.addView(header);

		LinearLayout actionsContainer = new LinearLayout(this);
		actionsContainer.setOrientation(LinearLayout.VERTICAL);
		root.addView(actionsContainer);

		java.util.List<ActionRow> rows = new java.util.ArrayList<>();

		final Runnable addRow = new Runnable() {
			@Override
			public void run() {
				final ActionRow rowHolder[] = {null};
				final Runnable pickTask = () -> {
					// Collect current state before dismissing
					java.util.List<AutoClickManager.AutoAction> currentSeq = new java.util.ArrayList<>();
					for (ActionRow r : rows) currentSeq.add(r.toAutoAction());
					int targetIndex = rows.indexOf(rowHolder[0]);

					dialog[0].dismiss();
					Toast.makeText(MicroActivity.this, R.string.auto_click_pick_instruction, Toast.LENGTH_LONG).show();
					
					// Use a transparent overlay to catch the next click
					View overlay = new View(MicroActivity.this);
					overlay.setBackgroundColor(0x22000000);
					binding.displayableContainer.addView(overlay, new ViewGroup.LayoutParams(-1, -1));
					overlay.setOnTouchListener((v, event) -> {
						if (event.getAction() == MotionEvent.ACTION_DOWN) {
							int vx = (int) canvas.convertPointerX(event.getX());
							int vy = (int) canvas.convertPointerY(event.getY());
							
							// Update coordinates in the temporary sequence
							AutoClickManager.AutoAction targeted = currentSeq.get(targetIndex);
							targeted.x = vx;
							targeted.y = vy;
							
							binding.displayableContainer.removeView(overlay);
							showAutoClickDialog(currentSeq); // Re-open with results
							return true;
						}
						return false;
					});
				};
				rowHolder[0] = ActionRow.create(MicroActivity.this, dp, pickTask);
				actionsContainer.addView(rowHolder[0].view);
				rows.add(rowHolder[0]);
				rowHolder[0].btnRemove.setOnClickListener(v -> {
					actionsContainer.removeView(rowHolder[0].view);
					rows.remove(rowHolder[0]);
				});
			}
		};

		// Load initial sequence or start with one empty row
		if (initialSequence != null && !initialSequence.isEmpty()) {
			for (AutoClickManager.AutoAction action : initialSequence) {
				addRow.run();
				rows.get(rows.size() - 1).fromAutoAction(action);
			}
		} else {
			addRow.run();
		}

		Button btnAdd = new Button(this);
		btnAdd.setText(R.string.auto_click_add_action);
		btnAdd.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_input_add, 0, 0, 0);
		root.addView(btnAdd);

		btnAdd.setOnClickListener(v -> addRow.run());

		btnSave.setOnClickListener(v -> {
			EditText etFilename = new EditText(this);
			etFilename.setHint(R.string.auto_click_enter_filename);
			new AlertDialog.Builder(this)
					.setTitle(R.string.auto_click_save)
					.setView(etFilename)
					.setPositiveButton(android.R.string.ok, (d, w) -> {
						String name = etFilename.getText().toString();
						if (TextUtils.isEmpty(name)) return;
						File dir = new File(appPath, "autoclick");
						dir.mkdirs();
						File file = new File(dir, name + ".json");
						java.util.List<AutoClickManager.AutoAction> seq = new java.util.ArrayList<>();
						for (ActionRow r : rows) seq.add(r.toAutoAction());
						try {
							AutoClickManager.saveScript(file, seq);
							Toast.makeText(this, R.string.auto_click_script_saved, Toast.LENGTH_SHORT).show();
						} catch (IOException e) {
							Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
						}
					}).show();
		});

		btnLoad.setOnClickListener(v -> {
			File dir = new File(appPath, "autoclick");
			String[] files = dir.exists() ? dir.list((d, n) -> n.endsWith(".json")) : null;
			if (files == null || files.length == 0) {
				Toast.makeText(this, "No scripts found", Toast.LENGTH_SHORT).show();
				return;
			}
			new AlertDialog.Builder(this)
					.setTitle(R.string.auto_click_load)
					.setItems(files, (d, which) -> {
						try {
							java.util.List<AutoClickManager.AutoAction> seq = AutoClickManager.loadScript(new File(dir, files[which]));
							dialog[0].dismiss();
							showAutoClickDialog(seq);
						} catch (Exception e) {
							Toast.makeText(this, R.string.auto_click_invalid_script, Toast.LENGTH_SHORT).show();
						}
					}).show();
		});

		boolean isRunning = autoClickManager.isRunning();
		String posLabel = isRunning ? getString(R.string.auto_click_stop) : getString(R.string.auto_click_start);

		dialog[0] = new AlertDialog.Builder(this)
				.setTitle(R.string.auto_click)
				.setView(scroll)
				.setPositiveButton(posLabel, (d, w) -> {
					if (autoClickManager.isRunning()) {
						autoClickManager.stop();
						Toast.makeText(this, R.string.auto_click_stopped, Toast.LENGTH_SHORT).show();
						return;
					}
					java.util.List<AutoClickManager.AutoAction> sequence = new java.util.ArrayList<>();
					for (ActionRow row : rows) sequence.add(row.toAutoAction());
					lastAutoClickSequence = sequence;
					if (!sequence.isEmpty()) {
						autoClickManager.startSequence(canvas, sequence);
						Toast.makeText(this, R.string.auto_click_running, Toast.LENGTH_SHORT).show();
					}
				})
				.setNegativeButton(android.R.string.cancel, (d, w) -> {
					java.util.List<AutoClickManager.AutoAction> sequence = new java.util.ArrayList<>();
					for (ActionRow row : rows) sequence.add(row.toAutoAction());
					lastAutoClickSequence = sequence;
				})
				.setNeutralButton("New", (d, w) -> {
					lastAutoClickSequence = null;
					showAutoClickDialog(null);
				})
				.show();
	}

	private static int parseIntSafe(String s, int def) {
		try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
	}

	private static long parseLongSafe(String s, long def) {
		try { return Long.parseLong(s.trim()); } catch (Exception e) { return def; }
	}

	@Override
	public boolean onContextItemSelected(@NonNull MenuItem item) {
		if (current instanceof Form) {
			((Form) current).contextMenuItemSelected(item);
		} else if (current instanceof List) {
			AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
			((List) current).contextMenuItemSelected(item, info.position);
		}

		return super.onContextItemSelected(item);
	}

	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		ContextHolder.notifyOnActivityResult(requestCode, resultCode, data);
	}

	public String getAppName() {
		return appName;
	}

	private class SetCurrentEvent extends SimpleEvent {
		private final Displayable current;
		private final Displayable next;

		private SetCurrentEvent(Displayable current, Displayable next) {
			this.current = current;
			this.next = next;
		}

		@Override
		public void process() {
			closeOptionsMenu();
			if (current != null) {
				current.clearDisplayableView();
			}
			if (next instanceof Alert) {
				return;
			}
			binding.displayableContainer.removeAllViews();
			ActionBar actionBar = Objects.requireNonNull(getSupportActionBar());
			LinearLayout.LayoutParams layoutParams = (LinearLayout.LayoutParams) binding.toolbar.getLayoutParams();
			int toolbarHeight = 0;
			if (next instanceof Canvas) {
				hideSystemUI();
				if (!actionBarEnabled) {
					actionBar.hide();
				} else {
					final String title = next.getTitle();
					actionBar.setTitle(title == null ? appName : title);
					toolbarHeight = (int) (getToolBarHeight() / 1.5);
					layoutParams.height = toolbarHeight;
				}
			} else {
				showSystemUI();
				actionBar.show();
				final String title = next != null ? next.getTitle() : null;
				actionBar.setTitle(title == null ? appName : title);
				toolbarHeight = getToolBarHeight();
				layoutParams.height = toolbarHeight;
			}
			binding.overlayView.setLocation(0, toolbarHeight);
			binding.toolbar.setLayoutParams(layoutParams);
			invalidateOptionsMenu();
			if (next != null) {
				binding.displayableContainer.addView(next.getDisplayableView());
			}
		}
	}

	@Override
	protected void onDestroy() {
		autoClickManager.stop();
		binding = null;
		super.onDestroy();
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if (requestCode == 1) {
			synchronized (LocationProviderImpl.permissionLock) {
				LocationProviderImpl.permissionLock.notify();
			}
			LocationProviderImpl.permissionResult = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
		}
	}
}
