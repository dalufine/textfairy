/*
 * Copyright (C) 2007 The Android Open Source Project
 * Copyright (C) 2012,2013,2014,2015 Renard Wellnitz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.renard.ocr.cropimage;

import com.google.common.base.Optional;

import com.googlecode.leptonica.android.Box;
import com.googlecode.leptonica.android.Clip;
import com.googlecode.leptonica.android.Pix;
import com.googlecode.leptonica.android.Projective;
import com.googlecode.leptonica.android.Rotate;
import com.googlecode.tesseract.android.OCR;
import com.renard.ocr.DocumentGridActivity;
import com.renard.ocr.R;
import com.renard.ocr.help.HintDialog;
import com.renard.util.Util;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver.OnGlobalLayoutListener;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.Toast;
import android.widget.ViewSwitcher;

import butterknife.Bind;
import butterknife.ButterKnife;
import butterknife.OnClick;
import de.greenrobot.event.EventBus;

/**
 * The activity can crop specific region of interest from an image.
 */
public class CropImageActivity extends MonitoredActivity implements ImageBlurredDialog.BlurDialogClickListener {
    public static final int RESULT_NEW_IMAGE = RESULT_FIRST_USER + 1;
    private static final int HINT_DIALOG_ID = 2;
    private final Handler mHandler = new Handler();

    private int mRotation = 0;
    boolean mSaving;
    private Pix mPix;
    @Bind(R.id.toolbar)
    protected Toolbar mToolbar;
    @Bind(R.id.cropImageView)
    protected CropImageView mImageView;
    @Bind(R.id.crop_layout)
    protected ViewSwitcher mViewSwitcher;
    @Bind(R.id.item_rotate_left)
    protected ImageView mRotateLeft;
    @Bind(R.id.item_rotate_right)
    protected ImageView mRotateRight;
    @Bind(R.id.item_save)
    protected ImageView mSave;


    private CropHighlightView mCrop;
    private Optional<CropData> mCropData = Optional.absent();
    private Optional<PreparePixForCropTask> mPrepareTask = Optional.absent();

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        EventBus.getDefault().register(this);
        getWindow().setFormat(PixelFormat.RGBA_8888);
        setContentView(R.layout.activity_cropimage);
        ButterKnife.bind(this);
        initToolbar();
        initNavigationAsUp();
        startCropping();
    }


    @OnClick(R.id.item_rotate_left)
    public void onRotateLeft() {
        onRotateClicked(-1);
    }

    @OnClick(R.id.item_rotate_right)
    public void onRotateRight() {
        onRotateClicked(1);
    }

    private void onRotateClicked(int delta) {
        if (mCropData.isPresent()) {
            if (delta < 0) {
                delta = -delta * 3;
            }
            mRotation += delta;
            mRotation = mRotation % 4;
            mImageView.setImageBitmapResetBase(mCropData.get().getBitmap(), false, mRotation * 90);
            showDefaultCroppingRectangle(mCropData.get().getBitmap());
        }
    }

    private void startCropping() {
        mImageView.getViewTreeObserver().addOnGlobalLayoutListener(new OnGlobalLayoutListener() {

            @Override
            public void onGlobalLayout() {
                Bundle extras = getIntent().getExtras();
                final long nativePix = extras.getLong(DocumentGridActivity.EXTRA_NATIVE_PIX);
                final int rotation = extras.getInt(DocumentGridActivity.EXTRA_ROTATION);
                final int width = mViewSwitcher.getWidth();
                final int height = mViewSwitcher.getHeight();
                mPix = new Pix(nativePix);
                mRotation = rotation / 90;
                mPrepareTask = Optional.of(new PreparePixForCropTask(mPix, width, height));
                mPrepareTask.get().execute();
                mImageView.getViewTreeObserver().removeGlobalOnLayoutListener(this);
            }

        });
    }


    private void initNavigationAsUp() {
        final Drawable upArrow = getResources().getDrawable(R.drawable.ic_arrow_back_white);
        getSupportActionBar().setHomeAsUpIndicator(upArrow);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        mToolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    @Override
    protected int getHintDialogId() {
        return HINT_DIALOG_ID;
    }

    @SuppressWarnings("unused")
    public void onEventMainThread(final CropData cropData) {
        if (cropData.getBitmap() == null) {
            //should not happen. Scaling of the original document failed some how. Maybe out od memory?
            //TODO send GA event to monitor this
            Toast.makeText(this, R.string.could_not_load_image, Toast.LENGTH_LONG).show();
            onNewImageClicked();
        }
        mCropData = Optional.of(cropData);
        adjustOptionsMenu();
        mViewSwitcher.setDisplayedChild(1);

        mImageView.getViewTreeObserver().addOnGlobalLayoutListener(new OnGlobalLayoutListener() {

            @Override
            public void onGlobalLayout() {
                mImageView.setImageBitmapResetBase(cropData.getBitmap(), true, mRotation * 90);

                switch (cropData.getBlurriness().getBlurriness()) {
                    case NOT_BLURRED:
                        showDefaultCroppingRectangle(cropData.getBitmap());
                        break;
                    case MEDIUM_BLUR:
                    case STRONG_BLUR:
                        zoomToBlurredRegion(cropData);
                        setTitle(R.string.image_is_blurred);
                        ImageBlurredDialog dialog = ImageBlurredDialog.newInstance((float) cropData.getBlurriness().getBlurValue());
                        dialog.show(getSupportFragmentManager(), ImageBlurredDialog.TAG);
                        break;
                }

                mImageView.getViewTreeObserver().removeGlobalOnLayoutListener(this);
            }

        });

    }


    @Override
    protected Dialog onCreateDialog(int id, Bundle args) {
        switch (id) {
            case HINT_DIALOG_ID:
                return HintDialog.createDialog(this, R.string.crop_help_title, "file:///android_res/raw/crop_help.html");
        }
        return super.onCreateDialog(id, args);
    }


    private void adjustOptionsMenu() {
        if (mCropData.isPresent()) {
            mRotateLeft.setVisibility(View.VISIBLE);
            mRotateRight.setVisibility(View.VISIBLE);
            mSave.setVisibility(View.VISIBLE);
        } else {
            mRotateLeft.setVisibility(View.GONE);
            mRotateRight.setVisibility(View.GONE);
            mSave.setVisibility(View.GONE);
        }
    }

    @OnClick(R.id.item_save)
    void onSaveClicked() {
        if (!mCropData.isPresent() || mSaving || (mCrop == null)) {
            return;
        }
        mSaving = true;

        Util.startBackgroundJob(this, null, getText(R.string.cropping_image).toString(), new Runnable() {
            public void run() {
                try {
                    float scale = 1f / mCropData.get().getScaleResult().getScaleFactor();
                    Matrix scaleMatrix = new Matrix();
                    scaleMatrix.setScale(scale, scale);

                    final float[] trapezoid = mCrop.getTrapezoid();
                    final RectF perspectiveCorrectedBoundingRect = new RectF(mCrop.getPerspectiveCorrectedBoundingRect());
                    scaleMatrix.mapRect(perspectiveCorrectedBoundingRect);
                    Box bb = new Box((int) perspectiveCorrectedBoundingRect.left, (int) perspectiveCorrectedBoundingRect.top, (int) perspectiveCorrectedBoundingRect.width(), (int) perspectiveCorrectedBoundingRect.height());
                    Pix croppedPix = Clip.clipRectangle2(mPix, bb);
                    if (croppedPix == null) {
                        throw new IllegalStateException();
                    }

                    scaleMatrix.postTranslate(-bb.getX(), -bb.getY());
                    scaleMatrix.mapPoints(trapezoid);

                    final float[] dest = new float[]{0, 0, bb.getWidth(), 0, bb.getWidth(), bb.getHeight(), 0, bb.getHeight()};
                    Pix bilinear = Projective.projectiveTransform(croppedPix, dest, trapezoid);
                    if (bilinear == null) {
                        bilinear = croppedPix;
                    } else {
                        croppedPix.recycle();
                    }


                    if (mRotation != 0 && mRotation != 4) {
                        Pix rotatedPix = Rotate.rotateOrth(bilinear, mRotation);
                        bilinear.recycle();
                        bilinear = rotatedPix;
                    }
                    if (bilinear == null) {
                        throw new IllegalStateException();
                    }
                    Intent result = new Intent();
                    OCR.savePixToCacheDir(CropImageActivity.this, bilinear.copy());
                    result.putExtra(DocumentGridActivity.EXTRA_NATIVE_PIX, bilinear.getNativePix());
                    setResult(RESULT_OK, result);
                } catch (IllegalStateException e) {
                    setResult(RESULT_CANCELED);
                } finally {
                    mPix.recycle();
                    finish();
                }
            }
        }, mHandler);

    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        setResult(RESULT_CANCELED);
        mPix.recycle();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
        unbindDrawables(findViewById(android.R.id.content));
        if (mPrepareTask.isPresent()) {
            mPrepareTask.get().cancel(true);
            mPrepareTask = Optional.absent();
        }
        if (mCropData.isPresent()) {
            mCropData.get().recylce();
            mCropData = Optional.absent();
        }

    }

    private void unbindDrawables(View view) {
        if (view.getBackground() != null) {
            view.getBackground().setCallback(null);
        }
        if (view instanceof ViewGroup && !(view instanceof AdapterView)) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                unbindDrawables(((ViewGroup) view).getChildAt(i));
            }
            ((ViewGroup) view).removeAllViews();
        }
    }

    private void zoomToBlurredRegion(CropData data) {
        float width = data.getBlurriness().getPixBlur().getWidth();
        float height = data.getBlurriness().getPixBlur().getHeight();
        float widthScale = width / data.getBitmap().getWidth();
        float heightScale = height / data.getBitmap().getHeight();
        final Point c = data.getBlurriness().getMostBlurredRegion().getCenter();
        c.set((int) (c.x / widthScale), (int) (c.y / heightScale));
        float[] pts = {c.x, c.y};
        mImageView.getImageMatrix().mapPoints(pts);
        /*
        int w = (Math.min(mBitmap.getWidth(), mBitmap.getHeight())) / 25;

        Rect focusArea = new Rect((int) (Math.max(c.x-w,0)*widthScale), (int) (Math.max(c.y-w,0)*heightScale), (int) (Math.min(c.x+w,mBitmap.getWidth())*widthScale), (int) (Math.min(c.y+w,mBitmap.getHeight())*heightScale));

        //final int progressColor = getResources().getColor(R.color.progress_color);
        //final int edgeWidth = getResources().getDimensionPixelSize(R.dimen.crop_edge_width);
        Clip.clipRectangle2();

        BlurHighLightView hv = new BlurHighLightView(focusArea,progressColor,edgeWidth, mImageView.getImageMatrix());
        mImageView.add(hv);
        */
        mImageView.setMaxZoom(3);
        mImageView.zoomTo(3, pts[0], pts[1], 2000);
    }


    private void showDefaultCroppingRectangle(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        Rect imageRect = new Rect(0, 0, width, height);

        // make the default size about 4/5 of the width or height
        int cropWidth = Math.min(width, height) * 4 / 5;


        int x = (width - cropWidth) / 2;
        int y = (height - cropWidth) / 2;

        RectF cropRect = new RectF(x, y, x + cropWidth, y + cropWidth);

        CropHighlightView hv = new CropHighlightView(mImageView, imageRect, cropRect);

        mImageView.resetMaxZoom();
        mImageView.add(hv);
        mCrop = hv;
        mCrop.setFocus(true);
        mImageView.invalidate();
    }

    @Override
    public void onContinueClicked() {
        if (mCropData.isPresent()) {
            showDefaultCroppingRectangle(mCropData.get().getBitmap());
            setToolbarMessage(R.string.crop_title);
            mImageView.zoomTo(1, 500);
        }
    }

    @Override
    public void onNewImageClicked() {
        setResult(RESULT_NEW_IMAGE);
        mPix.recycle();
        finish();
    }
}

