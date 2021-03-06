/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.react.views.image;

import javax.annotation.Nullable;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.net.Uri;

import com.facebook.common.executors.UiThreadImmediateExecutorService;
import com.facebook.common.references.CloseableReference;
import com.facebook.common.util.UriUtil;
import com.facebook.csslayout.CSSConstants;
import com.facebook.csslayout.FloatUtil;
import com.facebook.datasource.DataSource;
import com.facebook.drawee.backends.pipeline.Fresco;
import com.facebook.drawee.controller.AbstractDraweeControllerBuilder;
import com.facebook.drawee.controller.BaseControllerListener;
import com.facebook.drawee.controller.ControllerListener;
import com.facebook.drawee.controller.ForwardingControllerListener;
import com.facebook.drawee.drawable.AutoRotateDrawable;
import com.facebook.drawee.drawable.ScalingUtils;
import com.facebook.drawee.generic.GenericDraweeHierarchy;
import com.facebook.drawee.generic.GenericDraweeHierarchyBuilder;
import com.facebook.drawee.generic.RoundingParams;
import com.facebook.drawee.view.GenericDraweeView;
import com.facebook.imagepipeline.common.ResizeOptions;
import com.facebook.imagepipeline.core.ImagePipeline;
import com.facebook.imagepipeline.datasource.BaseBitmapDataSubscriber;
import com.facebook.imagepipeline.image.CloseableImage;
import com.facebook.imagepipeline.image.ImageInfo;
import com.facebook.imagepipeline.request.BasePostprocessor;
import com.facebook.imagepipeline.request.ImageRequest;
import com.facebook.imagepipeline.request.ImageRequestBuilder;
import com.facebook.imagepipeline.request.Postprocessor;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.PromiseImpl;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableMapKeySetIterator;
import com.facebook.react.bridge.ReadableType;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.common.MapBuilder;
import com.facebook.react.common.SystemClock;
import com.facebook.react.uimanager.PixelUtil;
import com.facebook.react.uimanager.UIManagerModule;
import com.facebook.react.uimanager.events.EventDispatcher;
import com.facebook.react.uimanager.events.RCTEventEmitter;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;

import jp.co.cyberagent.android.gpuimage.GPUImage;
import jp.co.cyberagent.android.gpuimage.GPUImageFilter;
import jp.co.cyberagent.android.gpuimage.GPUImageFilterGroup;
import jp.co.cyberagent.android.gpuimage.GPUImageView;

/**
 * Wrapper class around Fresco's GenericDraweeView, enabling persisting props across multiple view
 * update and consistent processing of both static and network images.
 */
public class RCTGPUImageView extends GPUImageView {

    private static final String TEMP_FILE_PREFIX = "gpuimage_capture_";

    private GPUImageFilterGroup mFilterGroup;

    /**
     * Instantiates a new GPUImage object.
     *
     * @param context the context
     */
    public RCTGPUImageView(Context context) {
        super(context);
    }

    public void setFilters(ReadableArray filters) {
        boolean needUpdate = false;
        int count = filters.size();
        if (mFilterGroup==null || mFilterGroup.getFilters().size() != count) {
            needUpdate = true;
        }
        else {
            for (int i = 0 ;i<count; i++) {
                ReadableMap filterMap = filters.getMap(i);
                String name = filterMap.getString("name");
                GPUImageFilter filter = mFilterGroup.getFilters().get(i);
                if (!filter.getClass().getName().equals(name)) {
                    needUpdate = true;
                    break;
                }

            }
        }

        if (needUpdate) {
            mFilterGroup = new GPUImageFilterGroup();
            for (int i = 0; i<count; i++) {
                ReadableMap filterMap = filters.getMap(i);
                String name = filterMap.getString("name");
                if (name != null) {
                    try {
                        Class c = Class.forName("jp.co.cyberagent.android.gpuimage."+name);
                        GPUImageFilter imageFilter;
                        if (name.startsWith("IF")) {
                            Class[] cArg = new Class[1];
                            cArg[0] = Context.class;
                            imageFilter = (GPUImageFilter) c.getDeclaredConstructor(cArg).newInstance(getContext());
                        }
                        else {
                            imageFilter = (GPUImageFilter) c.newInstance();
                        }
                        mFilterGroup.addFilter(imageFilter);
                    } catch (Exception e) {
                        GPUImageFilter imageFilter = new GPUImageFilter();
                        mFilterGroup.addFilter(imageFilter);
                    }
                }
            }
            this.setFilter(mFilterGroup);
        }

        for (int i = 0 ; i<count ; i++) {
            ReadableMap filterMap = filters.getMap(i);
            if (filterMap.hasKey("params")) {
                ReadableMap params = filterMap.getMap("params");
                GPUImageFilter filter = mFilterGroup.getFilters().get(i);
                ReadableMapKeySetIterator interator = params.keySetIterator();
                while (interator.hasNextKey()) {
                    String key = interator.nextKey();
                    String setter = "set"+key.substring(0, 1).toUpperCase() + key.substring(1);
                    try {
                        ReadableType type = params.getType(key);

                        if (type == ReadableType.Number) {
                            Method field = filter.getClass().getMethod(setter, Float.TYPE);
                            double number = params.getDouble(key);
                            field.invoke(filter, (float)number);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                }
            }
        }

    }

    private Bitmap savedBitmap;
    private String lastImageUri;

    public void setSource(final String imageUri){
//        this.setImage(Uri.parse(imageUri));
        lastImageUri = imageUri;
        if (imageUri != null) {
            _getImage(Uri.parse(imageUri), null, new ImageCallback() {
                @Override
                public void invoke(@Nullable Bitmap bitmap) {
                    if (imageUri.equals(lastImageUri)) {
                        savedBitmap = bitmap;
                        setImage(bitmap);
                    }
                }
            });
        }
    }

    private void onCaptureSuccessed(String uri, int width, int height) {
        WritableMap event = Arguments.createMap();
        event.putString("uri", uri);
        event.putInt("width", width);
        event.putInt("height", height);
        ReactContext reactContext = (ReactContext)getContext();
        reactContext.getJSModule(RCTEventEmitter.class).receiveEvent(
                getId(),
                "topCaptureDone",
                event);
    }

    private void onCaptureFailed(String message) {
        WritableMap event = Arguments.createMap();
        event.putString("message", message);
        ReactContext reactContext = (ReactContext)getContext();
        reactContext.getJSModule(RCTEventEmitter.class).receiveEvent(
                getId(),
                "topCaptureFailed",
                event);
    }

    public void doCapture() {
        final int reactTag = getId();

        try {
            File dest = createTempFile(getContext());
            final int width = this.getMeasuredWidth();
            final int height = this.getMeasuredHeight();

            this.saveToPictures(dest.getParent(), dest.getName(), new OnPictureSavedListener(){
                @Override
                public void onPictureSaved(Uri uri) {
                    onCaptureSuccessed(uri.toString(), width, height);
                }
            });
        } catch (Throwable e){
            onCaptureFailed(e.getMessage());
        }

    }

    private static File createTempFile(Context context)
            throws IOException {
        File externalCacheDir = context.getExternalCacheDir();
        File internalCacheDir = context.getCacheDir();
        File cacheDir;
        if (externalCacheDir == null && internalCacheDir == null) {
            throw new IOException("No cache directory available");
        }
        if (externalCacheDir == null) {
            cacheDir = internalCacheDir;
        }
        else if (internalCacheDir == null) {
            cacheDir = externalCacheDir;
        } else {
            cacheDir = externalCacheDir.getFreeSpace() > internalCacheDir.getFreeSpace() ?
                    externalCacheDir : internalCacheDir;
        }
        return File.createTempFile(TEMP_FILE_PREFIX, ".jpg", cacheDir);
    }

    private interface ImageCallback {
        void invoke(@Nullable Bitmap bitmap);
    }

    private void _getImage(Uri uri, ResizeOptions resizeOptions, final ImageCallback imageCallback) {
        BaseBitmapDataSubscriber dataSubscriber = new BaseBitmapDataSubscriber() {
            @Override
            protected void onNewResultImpl(Bitmap bitmap) {
                bitmap = bitmap.copy(bitmap.getConfig(), true);
                imageCallback.invoke(bitmap);
            }

            @Override
            protected void onFailureImpl(DataSource<CloseableReference<CloseableImage>> dataSource) {
                imageCallback.invoke(null);
            }
        };

        ImageRequestBuilder builder = ImageRequestBuilder.newBuilderWithSource(uri);
        if (resizeOptions != null) {
            builder = builder.setResizeOptions(resizeOptions);
        }
        ImageRequest imageRequest = builder.build();

        ImagePipeline imagePipeline = Fresco.getImagePipeline();
        DataSource<CloseableReference<CloseableImage>> dataSource = imagePipeline.fetchDecodedImage(imageRequest, null);
        dataSource.subscribe(dataSubscriber, UiThreadImmediateExecutorService.getInstance());
    }

    private static Uri getResourceDrawableUri(Context context, String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        name = name.toLowerCase().replace("-", "_");
        int resId = context.getResources().getIdentifier(
                name,
                "drawable",
                context.getPackageName());

        if (resId == 0) {
            return null;
        } else {
            return new Uri.Builder()
                    .scheme(UriUtil.LOCAL_RESOURCE_SCHEME)
                    .path(String.valueOf(resId))
                    .build();
        }
    }
}
