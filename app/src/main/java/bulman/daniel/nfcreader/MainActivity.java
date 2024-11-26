package bulman.daniel.nfcreader;

import androidx.appcompat.app.AppCompatActivity;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.nfc.tech.MifareUltralight;
import android.nfc.tech.Ndef;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class MainActivity extends AppCompatActivity {
    private NfcAdapter nfcAdapter;
    private TextView textView;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        textView = findViewById(R.id.messageDisplay);
        if (nfcAdapter == null) {
            Toast.makeText(this, "This device doesn't support NFC!", Toast.LENGTH_SHORT).show();
            finish();
        }
        handleIntent(getIntent());
    }

    private void handleIntent(Intent intent) {
        String action = intent.getAction();
        if (NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action) ||
                NfcAdapter.ACTION_TECH_DISCOVERED.equals(action) ||
                NfcAdapter.ACTION_TAG_DISCOVERED.equals(action)) {

            Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag.class);
            byte[] data = null;

            // Check for NDEF format
            Ndef ndef = Ndef.get(tag);
            if (ndef != null) {
                try {
                    ndef.connect();
                    NdefMessage ndefMessage = ndef.getNdefMessage();
                    if (ndefMessage != null) {
                        data = ndefMessage.toByteArray();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    try {
                        ndef.close();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }

            // Check for other formats
            if (data == null) {
                for (String tech : tag.getTechList()) {
                    if (tech.equals("android.nfc.tech.MifareUltralight")) {
                        // Mifare Ultralight format
                        data = readMifareUltralight(tag);
                        break;
                    } else if (tech.equals("android.nfc.tech.NfcA")) {
                        // ISO-DEP (ISO 14443-4) format
                        data = readIsoDep(tag);
                        break;
                    }
                }
            }

            // Extract data from byte array
            if (data != null) {
                String text = new String(data, StandardCharsets.UTF_8);
                textView.setText("Data on card: " + text + "\n" + "ID: " + bytesToHexString(tag.getId()));
            }
            else{
                textView.setText("Data on card: Unavailable\n" + "ID: " + bytesToHexString(tag.getId()));
            }
        }
    }
    private byte[] readMifareUltralight(Tag tag) {
        MifareUltralight ultralight = MifareUltralight.get(tag);
        try {
            ultralight.connect();
            byte[] data = ultralight.readPages(4);
            return Arrays.copyOfRange(data, 0, 16);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                ultralight.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    private byte[] readIsoDep(Tag tag) {
        IsoDep isoDep = IsoDep.get(tag);
        try {
            isoDep.connect();
            int maxTransceiveLength = isoDep.getMaxTransceiveLength();
            byte[] command = {(byte)0x00, (byte)0xB0, (byte)0x00, (byte)0x00, (byte)0x00};
            byte[] response = null;
            int retries = 3;
            while(retries>0){
                try {
                    response = isoDep.transceive(command);
                    break;
                } catch (IOException e) {
                    e.printStackTrace();
                    retries--;
                    Thread.sleep(500);
                }
            }
            if (response == null) {
                throw new IOException("Failed to read data from card");
            }
            if (response.length == maxTransceiveLength) {
                // If the response has the maximum size, it may contain more data
                // Try reading more data in chunks
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                baos.write(response);
                int offset = response.length;
                command[2] = (byte)(offset >> 8);
                command[3] = (byte)(offset & 0xFF);
                while (true) {
                    response = isoDep.transceive(command);
                    if (response.length == 0) {
                        break;
                    }
                    baos.write(response);
                    offset += response.length;
                    command[2] = (byte)(offset >> 8);
                    command[3] = (byte)(offset & 0xFF);
                }
                response = baos.toByteArray();
            }
            return response;
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                isoDep.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return null;
    }
    @Override
    protected void onResume() {
        super.onResume();
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        IntentFilter[] intentFilters = new IntentFilter[]{};
        if (!nfcAdapter.isEnabled()) {
            Toast.makeText(this, "Please enable NFC in your device settings", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(Settings.ACTION_NFC_SETTINGS));
        }
        nfcAdapter.enableForegroundDispatch(this, pendingIntent, intentFilters, null);
    }
    @Override
    protected void onPause() {
        super.onPause();
        nfcAdapter.disableForegroundDispatch(this);
    }
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }
    private String bytesToHexString(byte[] src) {
        StringBuilder stringBuilder = new StringBuilder(" 0x");
        if (src == null || src.length == 0) {
            return null;
        }
        char[] buffer = new char[2];
        for (byte b : src) {
            buffer[0] = Character.forDigit((b >>> 4) & 0x0F, 16);
            buffer[1] = Character.forDigit(b & 0x0F, 16);
            stringBuilder.append(buffer);
        }
        return stringBuilder.toString();
    }
}