package com.orogersilva.barcodereader.barcode;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.content.res.TypedArray;
import android.hardware.Camera;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.Fragment;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.vision.MultiProcessor;
import com.google.android.gms.vision.barcode.Barcode;
import com.google.android.gms.vision.barcode.BarcodeDetector;
import com.orogersilva.barcodereader.R;
import com.orogersilva.barcodereader.camera.CameraSource;
import com.orogersilva.barcodereader.camera.CameraSourcePreview;
import com.orogersilva.barcodereader.camera.GraphicOverlay;

import java.io.IOException;
import java.util.List;

/**
 * Created by t.tsilva on 15/01/18.
 */
public class BarcodeReaderFragment extends Fragment implements View.OnTouchListener, BarcodeGraphicTracker.BarcodeGraphicTrackerListener {

    private static final String TAG = BarcodeReaderFragment.class.getSimpleName();

    // region FIELDS

    private static String mDeniedPermissionTitle;
    private static String mDeniedPermissionMessage;
    private static String mDenyButtonTitle;
    private static String mTryAgainButtonTitle;
    private static String mRequestPermissionRationaleMessage;
    private static String mRequestPermissionRationaleCancelButtonTitle;
    private static String mRequestPermissionRationaleSettingsButtonTitle;

    // endregion

    // region FACTORY METHODS

    public static BarcodeReaderFragment newInstance(String deniedPermissionTitle,
                                                    String deniedPermissionMessage,
                                                    String denyButtonTitle,
                                                    String tryAgainButtonTitle,
                                                    String requestPermissionRationaleMessage,
                                                    String requestPermissionRationaleCancelButtonTitle,
                                                    String requestPermissionRationaleSettingsButtonTitle) {

        mDeniedPermissionTitle = deniedPermissionTitle;
        mDeniedPermissionMessage = deniedPermissionMessage;
        mDenyButtonTitle = denyButtonTitle;
        mTryAgainButtonTitle = tryAgainButtonTitle;
        mRequestPermissionRationaleMessage = requestPermissionRationaleMessage;
        mRequestPermissionRationaleCancelButtonTitle = requestPermissionRationaleCancelButtonTitle;
        mRequestPermissionRationaleSettingsButtonTitle = requestPermissionRationaleSettingsButtonTitle;

        return new BarcodeReaderFragment();
    }

    // endregion

    // intent request code to handle updating play services if needed.
    private static final int RC_HANDLE_GMS = 9001;

    // permission request codes need to be < 256
    private static final int RC_HANDLE_CAMERA_PERM = 2;

    private static final int RC_PERMISSION_SETTINGS = 3;

    // constants used to pass extra data in the intent
    private boolean autoFocus = false;
    private boolean useFlash = false;
    private String beepSoundFile;
    public static final String BarcodeObject = "Barcode";
    private boolean isPaused = false;

    private CameraSource mCameraSource;
    private CameraSourcePreview mPreview;
    private GraphicOverlay<BarcodeGraphic> mGraphicOverlay;

    // helper objects for detecting taps and pinches.
    private ScaleGestureDetector scaleGestureDetector;
    private GestureDetector gestureDetector;
    private BarcodeReaderListener mListener;

    public BarcodeReaderFragment() {
        // Required empty public constructor
    }

    public void setListener(BarcodeReaderListener barcodeReaderListener) {
        mListener = barcodeReaderListener;
    }

    public void setBeepSoundFile(String fileName) {
        beepSoundFile = fileName;
    }

    public void pauseScanning() {
        isPaused = true;
    }

    public void resumeScanning() {
        isPaused = false;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View view = inflater.inflate(R.layout.fragment_barcode_reader, container, false);

        mPreview = view.findViewById(R.id.preview);
        mGraphicOverlay = view.findViewById(R.id.graphicOverlay);

        // Check for the camera permission before accessing the camera.  If the
        // permission is not granted yet, request permission.
        int rc = ActivityCompat.checkSelfPermission(getActivity(), Manifest.permission.CAMERA);
        if (rc == PackageManager.PERMISSION_GRANTED) {
            createCameraSource(autoFocus, useFlash);
        } else {
            requestCameraPermission();
        }

        gestureDetector = new GestureDetector(getActivity(), new CaptureGestureListener());
        scaleGestureDetector = new ScaleGestureDetector(getActivity(), new ScaleListener());

        view.setOnTouchListener(this);
        return view;
    }

    @Override
    public void onInflate(Context context, AttributeSet attrs, Bundle savedInstanceState) {
        super.onInflate(context, attrs, savedInstanceState);
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.BarcodeReader);
        autoFocus = a.getBoolean(R.styleable.BarcodeReader_auto_focus, true);
        useFlash = a.getBoolean(R.styleable.BarcodeReader_use_flash, false);
        a.recycle();
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        if (context instanceof BarcodeReaderListener) {
            mListener = (BarcodeReaderListener) context;
        }
    }

    private void requestCameraPermission() {
        Log.w(TAG, "Camera permission is not granted. Requesting permission");

        final String[] permissions = new String[]{Manifest.permission.CAMERA};

        requestPermissions(permissions, RC_HANDLE_CAMERA_PERM);
    }

    @SuppressLint("InlinedApi")
    private void createCameraSource(boolean autoFocus, boolean useFlash) {
        Context context = getActivity();

        BarcodeDetector barcodeDetector = new BarcodeDetector.Builder(context).build();
        BarcodeTrackerFactory barcodeFactory = new BarcodeTrackerFactory(mGraphicOverlay, this);
        barcodeDetector.setProcessor(
                new MultiProcessor.Builder<>(barcodeFactory).build());

        if (!barcodeDetector.isOperational()) {

            Log.w(TAG, "Detector dependencies are not yet available.");

            IntentFilter lowstorageFilter = new IntentFilter(Intent.ACTION_DEVICE_STORAGE_LOW);
            boolean hasLowStorage = getActivity().registerReceiver(null, lowstorageFilter) != null;

            if (hasLowStorage) {
                Toast.makeText(getActivity(), R.string.low_storage_error, Toast.LENGTH_LONG).show();
                Log.w(TAG, getString(R.string.low_storage_error));
            }
        }

        CameraSource.Builder builder = new CameraSource.Builder(getActivity(), barcodeDetector)
                .setFacing(CameraSource.CAMERA_FACING_BACK)
                .setRequestedPreviewSize(1600, 1024)
                .setRequestedFps(15.0f);

        // make sure that auto focus is an available option
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            builder = builder.setFocusMode(
                    autoFocus ? Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE : null);
        }

        mCameraSource = builder
                .setFlashMode(useFlash ? Camera.Parameters.FLASH_MODE_TORCH : null)
                .build();
    }

    @Override
    public void onResume() {
        super.onResume();
        startCameraSource();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mPreview != null) {
            mPreview.stop();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mPreview != null) {
            mPreview.release();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {

        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == RC_PERMISSION_SETTINGS) {
            requestCameraPermission();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode != RC_HANDLE_CAMERA_PERM) {
            Log.d(TAG, "Got unexpected permission result: " + requestCode);
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            return;
        }

        if (grantResults.length != 0) {

            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                Log.d(TAG, "Camera permission granted - initialize the camera source");
                // we have permission, so create the camerasource
                createCameraSource(autoFocus, useFlash);
                return;

            } else if (grantResults[0] == PackageManager.PERMISSION_DENIED) {

                if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {

                    android.support.v7.app.AlertDialog.Builder alertDialogBuilder =
                            new android.support.v7.app.AlertDialog.Builder(getActivity());

                    alertDialogBuilder.setTitle(mDeniedPermissionTitle)
                            .setMessage(mDeniedPermissionMessage)
                            .setPositiveButton(mTryAgainButtonTitle, new DialogInterface.OnClickListener() {

                                @Override
                                public void onClick(DialogInterface dialog, int id) {

                                    requestCameraPermission();
                                }
                            })
                            .setNegativeButton(mDenyButtonTitle, new DialogInterface.OnClickListener() {

                                @Override
                                public void onClick(DialogInterface dialog, int id) {

                                    getActivity().finish();
                                }
                            })
                            .show();

                } else {

                    android.support.v7.app.AlertDialog.Builder alertDialogBuilder =
                            new android.support.v7.app.AlertDialog.Builder(getActivity());

                    alertDialogBuilder.setMessage(mRequestPermissionRationaleMessage)
                            .setPositiveButton(mRequestPermissionRationaleSettingsButtonTitle, new DialogInterface.OnClickListener() {

                                @Override
                                public void onClick(DialogInterface dialogInterface, int i) {

                                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);

                                    Uri uri = Uri.fromParts("package", getActivity().getPackageName(), null);

                                    intent.setData(uri);

                                    startActivityForResult(intent, RC_PERMISSION_SETTINGS);
                                }
                            })
                            .setNegativeButton(mRequestPermissionRationaleCancelButtonTitle, new DialogInterface.OnClickListener() {

                                @Override
                                public void onClick(DialogInterface dialogInterface, int i) {

                                    getActivity().finish();
                                }
                            })
                            .show();
                }
            }
        }

        Log.e(TAG, "Permission not granted: results len = " + grantResults.length +
                " Result code = " + (grantResults.length > 0 ? grantResults[0] : "(empty)"));
    }

    private void startCameraSource() throws SecurityException {
        // check that the device has play services available.
        int code = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(
                getActivity());
        if (code != ConnectionResult.SUCCESS) {
            Dialog dlg =
                    GoogleApiAvailability.getInstance().getErrorDialog(getActivity(), code, RC_HANDLE_GMS);
            dlg.show();
        }

        if (mCameraSource != null) {
            try {
                mPreview.start(mCameraSource, mGraphicOverlay);
            } catch (IOException e) {
                Log.e(TAG, "Unable to start camera source.", e);
                mCameraSource.release();
                mCameraSource = null;
            }
        }
    }

    private boolean onTap(float rawX, float rawY) {
        // Find tap point in preview frame coordinates.
        int[] location = new int[2];
        mGraphicOverlay.getLocationOnScreen(location);
        float x = (rawX - location[0]) / mGraphicOverlay.getWidthScaleFactor();
        float y = (rawY - location[1]) / mGraphicOverlay.getHeightScaleFactor();

        // Find the barcode whose center is closest to the tapped point.
        Barcode best = null;
        float bestDistance = Float.MAX_VALUE;
        for (BarcodeGraphic graphic : mGraphicOverlay.getGraphics()) {
            Barcode barcode = graphic.getBarcode();
            if (barcode.getBoundingBox().contains((int) x, (int) y)) {
                // Exact hit, no need to keep looking.
                best = barcode;
                break;
            }
            float dx = x - barcode.getBoundingBox().centerX();
            float dy = y - barcode.getBoundingBox().centerY();
            float distance = (dx * dx) + (dy * dy);  // actually squared distance
            if (distance < bestDistance) {
                best = barcode;
                bestDistance = distance;
            }
        }

        if (best != null) {
            Intent data = new Intent();
            data.putExtra(BarcodeObject, best);

            // TODO - pass the scanned value
            getActivity().setResult(CommonStatusCodes.SUCCESS, data);
            getActivity().finish();
            return true;
        }
        return false;
    }

    @Override
    public boolean onTouch(View view, MotionEvent motionEvent) {
        boolean b = scaleGestureDetector.onTouchEvent(motionEvent);

        boolean c = gestureDetector.onTouchEvent(motionEvent);

        return b || c || view.onTouchEvent(motionEvent);
    }

    @Override
    public void onScanned(Barcode barcode) {
        if (mListener != null && !isPaused) {
            mListener.onScanned(barcode);
        }
    }

    @Override
    public void onScannedMultiple(List<Barcode> barcodes) {
        if (mListener != null && !isPaused) {
            mListener.onScannedMultiple(barcodes);
        }
    }

    @Override
    public void onBitmapScanned(SparseArray<Barcode> sparseArray) {
        if (mListener != null) {
            mListener.onBitmapScanned(sparseArray);
        }
    }

    @Override
    public void onScanError(String errorMessage) {
        if (mListener != null) {
            mListener.onScanError(errorMessage);
        }
    }

    private class CaptureGestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            return onTap(e.getRawX(), e.getRawY()) || super.onSingleTapConfirmed(e);
        }
    }

    private class ScaleListener implements ScaleGestureDetector.OnScaleGestureListener {

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            return false;
        }

        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            return true;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            mCameraSource.doZoom(detector.getScaleFactor());
        }
    }

    public void playBeep() {
        MediaPlayer m = new MediaPlayer();
        try {
            if (m.isPlaying()) {
                m.stop();
                m.release();
                m = new MediaPlayer();
            }

            AssetFileDescriptor descriptor = getActivity().getAssets().openFd(beepSoundFile != null ? beepSoundFile : "beep.mp3");
            m.setDataSource(descriptor.getFileDescriptor(), descriptor.getStartOffset(), descriptor.getLength());
            descriptor.close();

            m.prepare();
            m.setVolume(1f, 1f);
            m.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public interface BarcodeReaderListener {
        void onScanned(Barcode barcode);

        void onScannedMultiple(List<Barcode> barcodes);

        void onBitmapScanned(SparseArray<Barcode> sparseArray);

        void onScanError(String errorMessage);
    }
}
