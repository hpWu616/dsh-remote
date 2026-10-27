package com.example.dsh_apk;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class RelayWebViewInstrumentedTest {
    private WebView findWebView(View view) {
        if (view instanceof WebView) return (WebView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                WebView found = findWebView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test public void relayScriptHasWebCryptoInAndroidWebView() throws Exception {
        Intent intent = new Intent(InstrumentationRegistry.getInstrumentation()
                .getTargetContext(), MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity = InstrumentationRegistry.getInstrumentation()
                .startActivitySync(intent);
        try {
            WebView webView = findWebView(activity.findViewById(android.R.id.content));
            assertNotNull(webView);
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<String> result = new AtomicReference<>();
            activity.runOnUiThread(() -> webView.evaluateJavascript(
                    "typeof window.relayConnect + ':' + !!crypto.subtle", value -> {
                        result.set(value);
                        done.countDown();
                    }));
            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertEquals("\"function:true\"", result.get());
        } finally {
            activity.runOnUiThread(activity::finish);
        }
    }
}
