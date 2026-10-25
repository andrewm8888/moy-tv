package ru.mikulinskiy.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Кнопка микрофона (ассистент) открывает домашний экран. */
public class AssistActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(i);
        finish();
    }
}
