package com.ubidots.ubidots;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DialogFragment;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.ubidots.ubidots.fragments.ChangePushTimeFragment;
import com.ubidots.ubidots.services.PushLocationService;

import org.w3c.dom.Text;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

public class UbidotsActivity extends Activity {
    // Preferences
    private SharedPreferences mSharedPreferences;
    private SharedPreferences.Editor mEditor;

    // App info
    private int mTimeToPush = 1;
    private boolean mAlreadyRunning;

    // Activity stuff
    private Button mPushTimeButton;

    private Switch mSwitch;
    private Switch mSwitch1;
    private TextView mTextViewTitleVel;
    public static TextView mTextViewVel;

    // Check connection
    private ConnectionStatusReceiver mReceiver = new ConnectionStatusReceiver();
    private IntentFilter iFilter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);

    // Maps variables
    private GoogleMap mGoogleMap;
    private LatLng mUserLocation;
    private Marker mUserMarker;

    Handler h;
    final int RECIEVE_MESSAGE = 1;		// Status  for Handler
    private BluetoothAdapter btAdapter = null;
    private BluetoothSocket btSocket = null;
    private StringBuilder sb = new StringBuilder();
    String sbprint = "";
    private ConnectedThread mConnectedThread;
    private static final String TAG = "bluetooth";

    // SPP UUID service
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // MAC-address of Bluetooth module (you must edit this line)
    private static String address = "20:15:05:05:59:30";

    protected PowerManager.WakeLock mWakeLock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ubidots);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);


        if (getActionBar() != null) {
            // Modify the ActionBar
            getActionBar().setDisplayShowHomeEnabled(false);
            getActionBar().setHomeButtonEnabled(false);
            getActionBar().setDisplayUseLogoEnabled(false);
            getActionBar().setDisplayOptions(ActionBar.DISPLAY_SHOW_CUSTOM);
            getActionBar().setCustomView(R.layout.action_bar);

            // Set the options overflow menu always on top
            try {
                ViewConfiguration config = ViewConfiguration.get(this);
                Field menuKeyField = ViewConfiguration.class.getDeclaredField("sHasPermanentMenuKey");
                if (menuKeyField != null) {
                    menuKeyField.setAccessible(true);
                    menuKeyField.setBoolean(config, false);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        h = new Handler() {
            public void handleMessage(android.os.Message msg) { //(what, arg1, arg2, obj)


                switch (msg.what) {
                    case RECIEVE_MESSAGE:													// if receive massage
                        byte[] writeBuf = (byte[]) msg.obj;
                        int begin = (int)msg.arg1;
                        int end = (int)msg.arg2;

                        String writeMessage = new String(writeBuf);
                        writeMessage = writeMessage.substring(begin, end);

                        Log.d(TAG, "...readMessage:"+ writeMessage);

                        if(writeMessage.contains("SW1-A")){
                            //Toast.makeText(getBaseContext(), " Switch Abierto ", Toast.LENGTH_LONG).show();
                            mSwitch1.setChecked(true);
                        }else if(writeMessage.contains("SW1-C")){
                            //Toast.makeText(getBaseContext(), " Switch Cerrado ", Toast.LENGTH_LONG).show();
                            mSwitch1.setChecked(false);
                        }


                        break;
                }
            };
        };

        btAdapter = BluetoothAdapter.getDefaultAdapter();		// get Bluetooth adapter
        checkBTState();

        // Instantiate layout widgets
        mSwitch = (Switch) findViewById(R.id.toggleActivation);
        mSwitch1 = (Switch) findViewById(R.id.switch1);
        mTextViewTitleVel = (TextView) findViewById(R.id.tv_title_vel);
        mTextViewVel = (TextView) findViewById(R.id.tv_vel);

        mTextViewTitleVel.setText(getString(R.string.text_vel));
        mTextViewVel.setText(getString(R.string.velocidad));

        // Instantiate shared preferences
        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        mEditor = mSharedPreferences.edit();

        // Get preferences variables
        boolean firstTime = mSharedPreferences.getBoolean(Constants.FIRST_TIME, true);
        mAlreadyRunning = mSharedPreferences.getBoolean(Constants.SERVICE_RUNNING, false);
        mTimeToPush = mSharedPreferences.getInt(Constants.PUSH_TIME, 1);

        mTimeToPush = 5; //5 seg cada muestra
        mEditor.putInt(Constants.PUSH_TIME, mTimeToPush);
        mEditor.apply();

        // Set the text at the left of the Switch
        ((TextView) findViewById(R.id.toggleText)).setText((mAlreadyRunning) ?
                getString(R.string.enabled_text) : getString(R.string.disabled_text));
        // Put the switch at its position
        mSwitch.setChecked(mAlreadyRunning);

        ((TextView) findViewById(R.id.toggleText_SW1)).setText((getString(R.string.name_text_SW1)));

        // If it's the first time the user access to the application we should put the preference
        // about it into false, so we can continue entering this activity immediately
        if (firstTime) {
            mEditor.putBoolean(Constants.FIRST_TIME, false);
            mEditor.apply();
        }

        // Check if Google Maps is installed
        if (isGoogleMapsInstalled()) {
            // Instantiate the fragment containing the map in the layout
            mGoogleMap = ((MapFragment) getFragmentManager().findFragmentById(R.id.map)).getMap();

            // Get the location given by the system
            LocationManager location = (LocationManager) getSystemService(LOCATION_SERVICE);

            // Create a location that updates when the location has changed
            LocationListener locationListener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    mUserLocation = new LatLng(location.getLatitude(), location.getLongitude());
                    mGoogleMap.clear();
                    mUserMarker = mGoogleMap.addMarker(new MarkerOptions()
                            .position(mUserLocation)
                            .title(getString(R.string.location)));
                    mGoogleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(mUserLocation, 17));
                }

                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) { }

                @Override
                public void onProviderEnabled(String provider) { }

                @Override
                public void onProviderDisabled(String provider) { }
            };

            // Set the listener to the location manager
            location.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, locationListener);
        }


        // Set the listener when clicked the switch button
        mSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (!mAlreadyRunning) {
                    startRepeatingService();
                    mEditor.putBoolean(Constants.SERVICE_RUNNING, true);
                    ((TextView) findViewById(R.id.toggleText)).setText(getString(R.string.enabled_text));
                    createNotification();
                } else {
                    mEditor.putBoolean(Constants.SERVICE_RUNNING, false);
                    ((TextView) findViewById(R.id.toggleText)).setText(getString(R.string.disabled_text));
                    deleteNotification();
                }
                mAlreadyRunning = !mAlreadyRunning;
                mEditor.apply();
            }
        });
    }

    // Start the service
    public void startRepeatingService() {
        startService(new Intent(this, PushLocationService.class));
    }


    // Check if Google Maps is installed
    public boolean isGoogleMapsInstalled()
    {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo("com.google.android.apps.maps", 0);
            return true;
        } catch(PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    // Check if Google Play is available
    public void checkGooglePlayAvailability() {
        int requestCode = GooglePlayServicesUtil.isGooglePlayServicesAvailable(this);
        if (requestCode != ConnectionResult.SUCCESS) {
            GooglePlayServicesUtil.getErrorDialog(requestCode, this, 1337).show();
        }
    }

    private BluetoothSocket createBluetoothSocket(BluetoothDevice device) throws IOException {
        if(Build.VERSION.SDK_INT >= 10){
            try {
                final Method m = device.getClass().getMethod("createInsecureRfcommSocketToServiceRecord", new Class[] { UUID.class });
                return (BluetoothSocket) m.invoke(device, MY_UUID);
            } catch (Exception e) {
                Log.e(TAG, "Could not create Insecure RFComm Connection",e);
            }
        }
        return  device.createRfcommSocketToServiceRecord(MY_UUID);
    }

    // Create the notification to notify the user that the service is running
    public void createNotification() {
        String ns = Context.NOTIFICATION_SERVICE;
        String notificationTitle = getString(R.string.notification_title);

        Intent intent = new Intent(this, UbidotsActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, 0);

        // We should use Compat, because we are using API 14+
        NotificationCompat.Builder notification = new NotificationCompat.Builder(this)
                .setContentTitle(notificationTitle)
                .setSmallIcon(R.drawable.ic_stat_notify_logo)
                .setContentIntent(pendingIntent);

        // Build the notification
        Notification notificationCompat = notification.build();

        // Create the manager
        NotificationManager notificationManager = (NotificationManager) getSystemService(ns);
        notificationCompat.flags |= Notification.FLAG_ONGOING_EVENT;

        // Push the notification
        notificationManager.notify(Constants.NOTIFICATION_ID, notificationCompat);
    }

    // Delete the notification
    public void deleteNotification() {
        String ns = Context.NOTIFICATION_SERVICE;
        NotificationManager notificationManager = (NotificationManager) getSystemService(ns);
        notificationManager.cancel(Constants.NOTIFICATION_ID);
    }


    @Override
    protected void onPause() {

        try     {
            btSocket.close();
        } catch (IOException e2) {
            errorExit("Fatal Error", "In onPause() and failed to close socket." + e2.getMessage() + ".");
        }

        this.finish();

        super.onPause();
    }



    @Override
    public void onBackPressed() {
        new AlertDialog.Builder(this)
                .setMessage("Are you sure you want to exit?")
                .setCancelable(false)
                .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        UbidotsActivity.this.finish();
                    }
                })
                .setNegativeButton("No", null)
                .show();
    }

    private void checkBTState() {
        // Check for Bluetooth support and then check to make sure it is turned on
        // Emulator doesn't support Bluetooth and will return null
        if(btAdapter==null) {
            errorExit("Fatal Error", "Bluetooth not support");
        } else {
            if (btAdapter.isEnabled()) {
                Log.d(TAG, "...Bluetooth ON...");
            } else {
                //Prompt user to turn on Bluetooth
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent, 1);
            }
        }
    }

    private void errorExit(String title, String message){
        Toast.makeText(getBaseContext(), title + " - " + message, Toast.LENGTH_LONG).show();
        finish();
    }

    @Override
    protected void onResume() {
         this.registerReceiver(mReceiver, iFilter);
        checkGooglePlayAvailability();

        BluetoothDevice device = btAdapter.getRemoteDevice(address);

        // Two things are needed to make a connection:
        //   A MAC address, which we got above.
        //   A Service ID or UUID.  In this case we are using the
        //     UUID for SPP.

        try {
            btSocket = createBluetoothSocket(device);
        } catch (IOException e) {
            errorExit("Fatal Error", "In onResume() and socket create failed: " + e.getMessage() + ".");
        }

        // Discovery is resource intensive.  Make sure it isn't going on
        // when you attempt to connect and pass your message.
        btAdapter.cancelDiscovery();

        // Establish the connection.  This will block until it connects.
        Log.d(TAG, "...Connecting...");
        try {
            btSocket.connect();
            Log.d(TAG, "....Connection ok...");
        } catch (IOException e) {
            try {
                btSocket.close();
            } catch (IOException e2) {
                errorExit("Fatal Error", "In onResume() and unable to close socket during connection failure" + e2.getMessage() + ".");
            }
        }

        // Create a data stream so we can talk to server.
        Log.d(TAG, "...Create Socket...");

        mConnectedThread = new ConnectedThread(btSocket);
        mConnectedThread.start();

        super.onResume();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.ubidots, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_change_token) {
            mEditor.putBoolean(Constants.SERVICE_RUNNING, false);
            mEditor.putBoolean(Constants.FIRST_TIME, true);
            mEditor.putString(Constants.VARIABLE_ID, null);
            mEditor.putString(Constants.TOKEN, null);
            mEditor.putString(Constants.DATASOURCE_VARIABLE, null);
            mEditor.putInt(Constants.PUSH_TIME, 1);
            mEditor.apply();

            Intent i = new Intent(this, MainActivity.class);
            startActivity(i);
            finish();
        } else if (id == R.id.action_help) {
            String urlHelp = Constants.BROWSER_CONFIG.HELP_URL;
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setData(Uri.parse(urlHelp));
            startActivity(i);
        }

        return super.onOptionsItemSelected(item);
    }

    public class ConnectionStatusReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int connectionStatus = NetworkUtil.getConnectionStatus(context);
            if (connectionStatus == NetworkUtil.TYPE_MOBILE ||
                    connectionStatus == NetworkUtil.TYPE_WIFI) {
                //mPushTimeButton.setEnabled(true);
                mSwitch.setEnabled(true);
            } else {
                if (mSharedPreferences.getBoolean(Constants.SERVICE_RUNNING, false)) {
                    deleteNotification();
                }
                //mPushTimeButton.setEnabled(false);
                mSwitch.setChecked(false);
                mSwitch.setEnabled(false);
                mEditor.putBoolean(Constants.SERVICE_RUNNING, false);
                mEditor.apply();
            }
        }
    }

    private class ConnectedThread extends Thread {
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;

        public ConnectedThread(BluetoothSocket socket) {
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            // Get the input and output streams, using temp objects because
            // member streams are final
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) { }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        public void run() {
            byte[] buffer = new byte[1024];
            int begin = 0;
            int bytes = 0;
            // Keep listening to the InputStream until an exception occurs
            while (true) {
                try {
                    bytes += mmInStream.read(buffer, bytes, buffer.length - bytes);
                    for(int i = begin; i < bytes; i++) {
                        if(buffer[i] == "#".getBytes()[0]) {
                            h.obtainMessage(1, begin, i, buffer).sendToTarget();
                            begin = i + 1;
                            if(i == bytes
                                    -
                                    1) {
                                bytes = 0;
                                begin = 0;
                            }
                        }
                    }
                } catch (IOException e) {
                    break;
                }
            }
        }

        /* Call this from the main activity to send data to the remote device */
        public void write(String message) {
            Log.d(TAG, "...Data to send: " + message + "...");
            byte[] msgBuffer = message.getBytes();
            try {
                mmOutStream.write(msgBuffer);
            } catch (IOException e) {
                Log.d(TAG, "...Error data send: " + e.getMessage() + "...");
            }
        }
    }

}
