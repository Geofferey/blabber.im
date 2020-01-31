package de.pixart.messenger.ui.widget;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatTextView;

@SuppressLint("AppCompatCustomView")
public class CopyTextView extends AppCompatTextView {

    public CopyTextView(Context context) {
        super(context);
    }

    public CopyTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public CopyTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public interface CopyHandler {
        String transformTextForCopy(CharSequence text, int start, int end);
    }

    private CopyHandler copyHandler;

    public void setCopyHandler(CopyHandler copyHandler) {
        this.copyHandler = copyHandler;
    }

    @Override
    public boolean onTextContextMenuItem(int id) {
        final CharSequence text = getText();
        int min = 0;
        int max = text.length();
        if (isFocused()) {
            final int selStart = getSelectionStart();
            final int selEnd = getSelectionEnd();
            min = Math.max(0, Math.min(selStart, selEnd));
            max = Math.max(0, Math.max(selStart, selEnd));
        }
        String textForCopy = null;
        if (id == android.R.id.copy && copyHandler != null) {
            textForCopy = copyHandler.transformTextForCopy(getText(), min, max);
        }
        try {
            return super.onTextContextMenuItem(id);
        } finally {
            if (textForCopy != null) {
                ClipboardManager clipboard = (ClipboardManager) getContext().
                        getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText(null, textForCopy));
            }
        }
    }
}