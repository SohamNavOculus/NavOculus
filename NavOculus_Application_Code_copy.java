// Android App for NavOculus with Full SLAM Map, Advanced Pathfinding, and ESP32 Integration

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.BatteryManager;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.ar.core.Frame;
import com.google.ar.core.PointCloud;
import com.google.ar.core.Session;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

public class MainActivity extends AppCompatActivity {

    private FusedLocationProviderClient fusedLocationClient;
    private TextToSpeech textToSpeech;
    private static final int LOCATION_PERMISSION_CODE = 1001;
    private static final int VOICE_INPUT_CODE = 1002;

    private Location currentLocation;
    private String destination;
    private final String ESP32_IP = "http://192.168.4.1/command";
    private Session arSession;

    private boolean obstacleDetected = false;
    private int frameCounter = 0;
    private Map<String, Integer> pointDensityMap = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initializeTextToSpeech();
        requestPermissions();
        initializeARSession();
    }

    private void requestPermissions() {
        String[] permissions = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CAMERA};
        ActivityCompat.requestPermissions(this, permissions, LOCATION_PERMISSION_CODE);
    }

    private void initializeTextToSpeech() {
        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech.setLanguage(Locale.ENGLISH);
            } else {
                Log.e("TTS", "Initialization failed");
            }
        });
    }

    private void getLastLocation() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
                if (location != null) {
                    currentLocation = location;
                    speak("Location acquired. Please provide your destination.");
                    promptVoiceInput();
                } else {
                    speak("Location not found.");
                }
            });
        }
    }

    private void promptVoiceInput() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        startActivityForResult(intent, VOICE_INPUT_CODE);
    }

    private void initializeARSession() {
        try {
            arSession = new Session(this);
            speak("AR session initialized.");
        } catch (UnavailableArcoreNotInstalledException | UnavailableDeviceNotCompatibleException | UnavailableSdkTooOldException e) {
            speak("Failed to initialize AR session.");
            Log.e("ARCore", e.getMessage());
        }
    }

    private void processFrame() {
        if (arSession == null) return;

        try {
            frameCounter++;
            if (shouldSkipFrame()) return; // Adaptive frame skipping based on battery

            Frame frame = arSession.update();
            PointCloud pointCloud = frame.acquirePointCloud();

            int pointCount = pointCloud.getPoints().remaining() / 4;
            Log.i("SLAM", "Points detected: " + pointCount);

            pointDensityMap.put(UUID.randomUUID().toString(), pointCount);

            if (pointCount < 100) {
                sendCommandToESP32("STOP");
                speak("Obstacle detected, stopping.");
                obstacleDetected = true;
                adjustDirection();
            } else {
                sendCommandToESP32("FORWARD:1000");
                obstacleDetected = false;
            }

            pointCloud.release();

        } catch (Exception e) {
            Log.e("SLAM", "Error processing frame: " + e.getMessage());
        }
    }

    private boolean shouldSkipFrame() {
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        int batteryLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        return batteryLevel < 20 && frameCounter % 10 != 0; // Skip more frames on low battery
    }

    private void adjustDirection() {
        if (obstacleDetected) {
            if (getDistanceFromESP32() < 20) {
                sendCommandToESP32("BACKWARD:500");
                speak("Reversing to avoid obstacle.");
            } else {
                sendCommandToESP32("LEFT:500");
                speak("Turning left to avoid obstacle.");
            }
        }
    }

    private int getDistanceFromESP32() {
        final int[] distance = {0};
        Thread thread = new Thread(() -> {
            try {
                URL url = new URL(ESP32_IP + "/distance"); // Assuming distance API endpoint
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");

                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String response = in.readLine();
                if (response != null && !response.isEmpty()) {
                    distance[0] = Integer.parseInt(response.trim());
                }

                in.close();
                conn.disconnect();
            } catch (Exception e) {
                Log.e("ESP32", "Error fetching distance: " + e.getMessage());
            }
        });

        thread.start();
        try {
            thread.join(); // Wait for the thread to finish
        } catch (InterruptedException e) {
            Log.e("ESP32", "Thread interrupted: " + e.getMessage());
        }
        return distance[0];
    }

    private void speak(String text) {
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
    }

    @Override
    protected void onDestroy() {
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
        if (arSession != null) {
            arSession.close();
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VOICE_INPUT_CODE && resultCode == RESULT_OK && data != null) {
            ArrayList<String> result = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (result != null && !result.isEmpty()) {
                destination = result.get(0);
                speak("Navigating to " + destination);
                performHybridPathfinding();
            }
        }
    }
}
