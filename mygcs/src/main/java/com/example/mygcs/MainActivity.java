package com.example.mygcs;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PointF;
import android.os.AsyncTask;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.v4.app.FragmentManager;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.naver.maps.geometry.LatLng;
import com.naver.maps.map.CameraUpdate;
import com.naver.maps.map.MapFragment;
import com.naver.maps.map.NaverMap;
import com.naver.maps.map.OnMapReadyCallback;
import com.naver.maps.map.overlay.InfoWindow;
import com.naver.maps.map.overlay.Marker;
import com.naver.maps.map.overlay.Overlay;
import com.naver.maps.map.overlay.PolygonOverlay;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;

import com.o3dr.android.client.ControlTower;
import com.o3dr.android.client.Drone;
import com.o3dr.android.client.apis.ControlApi;
import com.o3dr.android.client.apis.ExperimentalApi;
import com.o3dr.android.client.apis.VehicleApi;
import com.o3dr.android.client.apis.solo.SoloCameraApi;
import com.o3dr.android.client.interfaces.DroneListener;
import com.o3dr.android.client.interfaces.LinkListener;
import com.o3dr.android.client.interfaces.TowerListener;
import com.o3dr.android.client.utils.video.DecoderListener;
import com.o3dr.android.client.utils.video.MediaCodecManager;
import com.o3dr.services.android.lib.coordinate.LatLong;
import com.o3dr.services.android.lib.coordinate.LatLongAlt;
import com.o3dr.services.android.lib.drone.attribute.AttributeEvent;
import com.o3dr.services.android.lib.drone.attribute.AttributeType;
import com.o3dr.services.android.lib.drone.companion.solo.SoloAttributes;
import com.o3dr.services.android.lib.drone.companion.solo.SoloState;
import com.o3dr.services.android.lib.drone.connection.ConnectionParameter;
import com.o3dr.services.android.lib.drone.connection.ConnectionType;
import com.o3dr.services.android.lib.drone.property.Altitude;
import com.o3dr.services.android.lib.drone.property.Gps;
import com.o3dr.services.android.lib.drone.property.Home;
import com.o3dr.services.android.lib.drone.property.Speed;
import com.o3dr.services.android.lib.drone.property.State;
import com.o3dr.services.android.lib.drone.property.Type;
import com.o3dr.services.android.lib.drone.property.VehicleMode;
import com.o3dr.services.android.lib.gcs.link.LinkConnectionStatus;
import com.o3dr.services.android.lib.model.AbstractCommandListener;
import com.o3dr.services.android.lib.model.SimpleCommandListener;

public class MainActivity extends AppCompatActivity implements OnMapReadyCallback, DroneListener, TowerListener, LinkListener {
    private static final String TAG = MainActivity.class.getSimpleName();

    NaverMap myMap;

    int count_Basic =1, count_Hybrid =1, count_Navi =1;
    int n =0;

    private final Handler handler = new Handler();
    private Drone drone;
    private ControlTower controlTower;
    private int droneType = Type.TYPE_UNKNOWN;

    private static final int DEFAULT_UDP_PORT = 14550;
    private static final int DEFAULT_USB_BAUD_RATE = 57600;

    private Spinner modeSelector;

    Handler mainHandler;

    @Override
    public void onCreate (Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        final Context context = getApplicationContext();
        this.controlTower = new ControlTower(context);
        this.drone = new Drone(context);

        this.modeSelector = (Spinner) findViewById(R.id.modeSelect);
        this.modeSelector.setOnItemSelectedListener(new Spinner.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                onFlightModeSelected(view);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Do nothing
            }
        });

        FragmentManager fm = getSupportFragmentManager();
        MapFragment mapFragment = (MapFragment) fm.findFragmentById(R.id.map_fragment);

        if (mapFragment == null) {
            mapFragment = MapFragment.newInstance();
            fm.beginTransaction().add(R.id.map_fragment, mapFragment).commit();
        }
        mapFragment.getMapAsync((OnMapReadyCallback) this);

        mainHandler = new Handler(getApplicationContext().getMainLooper());
    }

    @Override
    public void onStart() {
        super.onStart();
        this.controlTower.connect(this);
        updateVehicleModesForType(this.droneType);
    }

    @Override
    public void onStop() {
        super.onStop();
        if (this.drone.isConnected()) {
            this.drone.disconnect();
            updateConnectedButton(false);
        }

        this.controlTower.unregisterDrone(this.drone);
        this.controlTower.disconnect();
    }
 //=====================================================================================================

    public void onBtnConnectTap(View view) {
        if (this.drone.isConnected()) {
            this.drone.disconnect();
        } else {
            ConnectionParameter connectionParams = ConnectionParameter.newUdpConnection(null);
            this.drone.connect(connectionParams);
        }
    }

    public void onFlightModeSelected(View view) {
        VehicleMode vehicleMode = (VehicleMode) this.modeSelector.getSelectedItem();

        VehicleApi.getApi(this.drone).setVehicleMode(vehicleMode, new AbstractCommandListener() {
            @Override
            public void onSuccess() {
                alertUser("Vehicle mode change successful.");
            }

            @Override
            public void onError(int executionError) {
                alertUser("Vehicle mode change failed: " + executionError);
            }

            @Override
            public void onTimeout() {
                alertUser("Vehicle mode change timed out.");
            }
        });
    }

    public void onArmButtonTap(View view) {
        State vehicleState = this.drone.getAttribute(AttributeType.STATE);

        if (vehicleState.isFlying()) {
            // Land
            VehicleApi.getApi(this.drone).setVehicleMode(VehicleMode.COPTER_LAND, new SimpleCommandListener() {
                @Override
                public void onError(int executionError) {
                    alertUser("Unable to land the vehicle.");
                }

                @Override
                public void onTimeout() {
                    alertUser("Unable to land the vehicle.");
                }
            });
        } else if (vehicleState.isArmed()) {
            // Take off
            ControlApi.getApi(this.drone).takeoff(10, new AbstractCommandListener() {

                @Override
                public void onSuccess() {
                    alertUser("Taking off...");
                }

                @Override
                public void onError(int i) {
                    alertUser("Unable to take off.");
                }

                @Override
                public void onTimeout() {
                    alertUser("Unable to take off.");
                }
            });
        } else if (!vehicleState.isConnected()) {
            // Connect
            alertUser("Connect to a drone first");
        } else {
            // Connected but not Armed
            VehicleApi.getApi(this.drone).arm(true, false, new SimpleCommandListener() {
                @Override
                public void onError(int executionError) {
                    alertUser("Unable to arm vehicle.");
                }

                @Override
                public void onTimeout() {
                    alertUser("Arming operation timed out.");
                }
            });
        }
    }


    @Override
    public void onDroneEvent(String event, Bundle extras) {
        switch (event) {
            case AttributeEvent.STATE_CONNECTED:
                alertUser("Drone Connected");
                updateConnectedButton(this.drone.isConnected());
                updateArmButton();
                //checkSoloState();
                break;

            case AttributeEvent.STATE_DISCONNECTED:
                alertUser("Drone Disconnected");
                updateConnectedButton(this.drone.isConnected());
                updateArmButton();
                break;

            case AttributeEvent.STATE_UPDATED:
            case AttributeEvent.STATE_ARMING:
                updateArmButton();
                break;

            case AttributeEvent.TYPE_UPDATED:
                Type newDroneType = this.drone.getAttribute(AttributeType.TYPE);
                if (newDroneType.getDroneType() != this.droneType) {
                    this.droneType = newDroneType.getDroneType();
                    updateVehicleModesForType(this.droneType);
                }
                break;

            case AttributeEvent.STATE_VEHICLE_MODE:
                updateVehicleMode();
                break;

            case AttributeEvent.SPEED_UPDATED:
                updateSpeed();
                break;

            case AttributeEvent.ALTITUDE_UPDATED:
                updateAltitude();
                break;

            case AttributeEvent.HOME_UPDATED:
                //updateDistanceFromHome();
                break;

            default:
                // Log.i("DRONE_EVENT", event); //Uncomment to see events from the drone
                break;
        }

    }

    @Override
    public void onDroneServiceInterrupted(String errorMsg) {

    }

    @Override
    public void onLinkStateUpdated(@NonNull LinkConnectionStatus connectionStatus) {
        switch(connectionStatus.getStatusCode()){
            case LinkConnectionStatus.FAILED:
                Bundle extras = connectionStatus.getExtras();
                String msg = null;
                if (extras != null) {
                    msg = extras.getString(LinkConnectionStatus.EXTRA_ERROR_MSG);
                }
                alertUser("Connection Failed:" + msg);
                break;
        }
    }

    @Override
    public void onTowerConnected() {
        alertUser("DroneKit-Android Connected");
        this.controlTower.registerDrone(this.drone, this.handler);
        this.drone.registerDroneListener(this);
    }

    @Override
    public void onTowerDisconnected() { alertUser("DroneKit-Android Interrupted"); }


    protected void updateConnectedButton(Boolean isConnected) {
        Button connectButton = (Button) findViewById(R.id.connect);
        if (isConnected) {
            connectButton.setText("Disconnect");
        } else {
            connectButton.setText("Connect");
        }
    }

    protected void updateArmButton() {
        State vehicleState = this.drone.getAttribute(AttributeType.STATE);
        Button armButton = (Button) findViewById(R.id.btnArmTakeOff);

        if (!this.drone.isConnected()) {
            armButton.setVisibility(View.INVISIBLE);
        } else {
            armButton.setVisibility(View.VISIBLE);
        }

        if (vehicleState.isFlying()) {
            // Land
            armButton.setText("LAND");
        } else if (vehicleState.isArmed()) {
            // Take off
            armButton.setText("TAKE OFF");
        } else if (vehicleState.isConnected()) {
            // Connected but not Armed
            armButton.setText("ARM");
        }
    }

    protected void updateVehicleModesForType(int droneType) {
        List<VehicleMode> vehicleModes = VehicleMode.getVehicleModePerDroneType(droneType);
        ArrayAdapter<VehicleMode> vehicleModeArrayAdapter = new ArrayAdapter<VehicleMode>(this, android.R.layout.simple_spinner_item, vehicleModes);
        vehicleModeArrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        this.modeSelector.setAdapter(vehicleModeArrayAdapter);
    }

    protected void updateVehicleMode() {
        State vehicleState = this.drone.getAttribute(AttributeType.STATE);
        VehicleMode vehicleMode = vehicleState.getVehicleMode();
        ArrayAdapter arrayAdapter = (ArrayAdapter) this.modeSelector.getAdapter();
        this.modeSelector.setSelection(arrayAdapter.getPosition(vehicleMode));
    }

    protected void updateSpeed() {
        TextView speedTextView = (TextView) findViewById(R.id.speedValueTextView);
        Speed droneSpeed = this.drone.getAttribute(AttributeType.SPEED);
        speedTextView.setText(String.format("%3.1f", droneSpeed.getGroundSpeed()) + "m/s");
    }

    protected void updateAltitude() {
        TextView altitudeTextView = (TextView) findViewById(R.id.altitudeValueTextView);
        Altitude droneAltitude = this.drone.getAttribute(AttributeType.ALTITUDE);
        altitudeTextView.setText(String.format("%3.1f", droneAltitude.getAltitude()) + "m");
    }

    protected void alertUser(String message) {
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
        Log.d(TAG, message);
    }


    public class MyAsyncTask extends AsyncTask<LatLng,String, String> {

        @Override
        protected String doInBackground(LatLng... latLngs) {

            String strCoord = String.valueOf(latLngs[0].longitude) + "," + String.valueOf(latLngs[0].latitude);
            Log.d("myLog", strCoord);

            StringBuilder sb = new StringBuilder();
            StringBuilder urlBuilder =
                    new StringBuilder("https://naveropenapi.apigw.ntruss.com/map-reversegeocode/v2/gc?request=coordsToaddr&coords=" +strCoord+ "&sourcecrs=epsg:4326&output=json&orders=addr");
            try{
                URL url = new URL(urlBuilder.toString());
                HttpsURLConnection http = (HttpsURLConnection)url.openConnection();
                http.setRequestProperty("Content-type", "application/json");
                http.getRequestProperty("X-NCP-APIGW-API-KEY-ID:{b2swhahkzz}");
                http.getRequestProperty("X-NCP-APIGW-API-KEY:{p4QGLMdzuXMdbK8BBCgIZHVo7MKKtc6mK5k6ScaI}");
                http.setRequestMethod("GET");
                http.connect();

                InputStreamReader in = new InputStreamReader(http.getInputStream(),"utf-8");
                BufferedReader rd;
//                Log.d("getResponseCode: ", http.getResponseCode()+"");
                if(http.getResponseCode() >=200 && http.getResponseCode() <= 300){
                    rd = new BufferedReader(in);
                } else {
                    rd = new BufferedReader(in);
                }

                String line;
                while ((line = rd.readLine()) != null){
                    sb.append(line).append("\n");
                }

                JsonParser parser = new JsonParser();
                JsonObject jsonObject;
                JsonObject jsonObject2;
                String x = "";
                String y = "";

                jsonObject = (JsonObject) parser.parse(sb.toString());
                JsonArray jsonArray = (JsonArray) jsonObject.get("results");
                Log.d("myLog3", jsonArray.toString());

                for(int i=0;i<jsonArray.size();i++){
                    jsonObject2 = (JsonObject)jsonArray.get(i);

                    Log.d("myLog2", jsonObject2.toString());
                    //jsonObject2 = jsonArray.json();
                    if(null != jsonObject2.get("x")){
                        x = (String) jsonObject2.get("x").toString();
                    }
                    if(null != jsonObject2.get("y")){
                        x = (String) jsonObject2.get("y").toString();
                    }
                }
                rd.close();
                in.close();
                http.disconnect();

            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            } catch (ProtocolException e) {
                e.printStackTrace();
            } catch (MalformedURLException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
            return null;
        }
    }

    @Override
    public void onMapReady(@NonNull final NaverMap naverMap) {

        this.myMap = naverMap;

        final Button button_Basic = (Button)findViewById(R.id.button);
        final Button button_Hybrid = (Button)findViewById(R.id.button2);
        final Button button_Terrain = (Button)findViewById(R.id.button3);
        final ToggleButton toggleButton = (ToggleButton)findViewById(R.id.toggleButton);
        toggleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if(toggleButton.isChecked()){
                    naverMap.setLayerGroupEnabled(NaverMap.LAYER_GROUP_BUILDING, false);
                    naverMap.setLayerGroupEnabled(NaverMap.LAYER_GROUP_CADASTRAL, true);
                }else{
                    naverMap.setLayerGroupEnabled(NaverMap.LAYER_GROUP_CADASTRAL, false);
                    naverMap.setLayerGroupEnabled(NaverMap.LAYER_GROUP_BUILDING, true);
                }
            }
        });
        button_Basic.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                naverMap.setMapType(NaverMap.MapType.Basic);
                if(count_Basic++ % 2 == 0){
                    button_Hybrid.setVisibility(view.GONE);
                    button_Terrain.setVisibility(view.GONE);
                }else{
                    button_Hybrid.setVisibility(view.VISIBLE);
                    button_Terrain.setVisibility(view.VISIBLE);
                }
            }
        });
        button_Hybrid.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                naverMap.setMapType(NaverMap.MapType.Hybrid);

                if(count_Hybrid++ % 2 == 0){
                    button_Basic.setVisibility(view.VISIBLE);
                    button_Terrain.setVisibility(view.VISIBLE);
                }else{
                    button_Basic.setVisibility(view.GONE);
                    button_Terrain.setVisibility(view.GONE);
                }
            }
        });
        button_Terrain.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                naverMap.setMapType(NaverMap.MapType.Terrain);

                if(count_Navi++ % 2 == 0){
                    button_Basic.setVisibility(view.VISIBLE);
                    button_Hybrid.setVisibility(view.VISIBLE);
                }else{
                    button_Basic.setVisibility(view.GONE);
                    button_Hybrid.setVisibility(view.GONE);
                }
            }
        });

        InfoWindow infoWindow = new InfoWindow();
        infoWindow.setAdapter(new InfoWindow.DefaultTextAdapter(getApplicationContext()) {
            @NonNull
            @Override
            public CharSequence getText(@NonNull InfoWindow infoWindow) {
                return (CharSequence)infoWindow.getMarker().getTag();
            }
        }); // 마커에 정보띄우기
        Overlay.OnClickListener listener = overlay -> {
            Marker marker = (Marker)overlay;

            if (marker.getInfoWindow() == null) {
                // 현재 마커에 정보 창이 열려있지 않을 경우 엶
                infoWindow.open(marker);
            } else {
                // 이미 현재 마커에 정보 창이 열려있을 경우 닫음
                infoWindow.close();
            }
            return true;
        }; //마커 on/off

        naverMap.setOnMapClickListener(new NaverMap.OnMapClickListener() {
            List<LatLng> coords = new ArrayList<>();
            @Override
            public void onMapClick(@NonNull PointF pointF, @NonNull LatLng latLng) {
                Marker mark = new Marker();
                mark.setPosition(latLng);
                mark.setMap(naverMap);

                PolygonOverlay polygon = new PolygonOverlay();
                coords.add(new LatLng(latLng.latitude,latLng.longitude));

                if(coords.size() > 2){
                    polygon.setCoords(coords);
                    polygon.setMap(naverMap);
                    polygon.setOutlineWidth(5);
                    polygon.setOutlineColor(Color.GREEN);
                }
                if(coords.size() > 4) {
                    polygon.setMap(null);
                    polygon.setCoords(coords);
                    polygon.setMap(naverMap);
                }

                Log.d("coords size : ",Integer.toString(coords.size()));


                mark.setTag("위도: " + latLng.latitude + "경도: " + latLng.longitude);

                mark.setOnClickListener(listener);
//                MyAsyncTask myAsyncTask = new MyAsyncTask();
//                myAsyncTask.execute(latLng);

            }
        });

        CameraUpdate cameraUpdate = CameraUpdate.scrollTo(new LatLng(35.945379, 126.682170));
        naverMap.moveCamera(cameraUpdate);

        Marker marker1 = new Marker();
        marker1.setPosition(new LatLng(35.945379, 126.682170)); //군산대학교 아카데미
        marker1.setMap(naverMap);
        marker1.setTag("군산대학교");
        marker1.setOnClickListener(listener);


    }
}
