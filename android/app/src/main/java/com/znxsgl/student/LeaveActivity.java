package com.znxsgl.student;

import android.Manifest;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.znxsgl.student.network.ApiService;
import com.znxsgl.student.network.RetrofitClient;

import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 请假 / 临时外出：申请、查看状态、取消、返校定位拍照打卡。
 */
public class LeaveActivity extends AppCompatActivity {

    public static final String EXTRA_TYPE = "type";
    private static final int REQ_CAMERA = 7001;
    private static final int REQ_LOCATION = 7002;

    private static final SimpleDateFormat FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);

    private String token;
    private String type = "LEAVE";
    private Calendar startCal;
    private Calendar endCal;
    private Long pendingReturnId = null;

    private TextView tvTitle, tabLeave, tabOuting, tvStart, tvEnd, tvRuleHint;
    private EditText etReason, etDestination, etParentPhone;
    private CheckBox cbParent;
    private Button btnSubmit;
    private LinearLayout boxRecords;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_leave);

        SharedPreferences prefs = getSharedPreferences("znxsgl", 0);
        token = "Bearer " + prefs.getString("token", "");

        String fromIntent = getIntent().getStringExtra(EXTRA_TYPE);
        if ("OUTING".equals(fromIntent)) type = "OUTING";

        tvTitle = findViewById(R.id.tvTitle);
        tabLeave = findViewById(R.id.tabLeave);
        tabOuting = findViewById(R.id.tabOuting);
        tvStart = findViewById(R.id.tvStart);
        tvEnd = findViewById(R.id.tvEnd);
        tvRuleHint = findViewById(R.id.tvRuleHint);
        etReason = findViewById(R.id.etReason);
        etDestination = findViewById(R.id.etDestination);
        etParentPhone = findViewById(R.id.etParentPhone);
        cbParent = findViewById(R.id.cbParent);
        btnSubmit = findViewById(R.id.btnSubmit);
        boxRecords = findViewById(R.id.boxRecords);

        findViewById(R.id.btnClose).setOnClickListener(v -> finish());
        tabLeave.setOnClickListener(v -> setType("LEAVE"));
        tabOuting.setOnClickListener(v -> setType("OUTING"));
        tvStart.setOnClickListener(v -> pickDateTime(true));
        tvEnd.setOnClickListener(v -> pickDateTime(false));
        btnSubmit.setOnClickListener(v -> submit());

        startCal = Calendar.getInstance();
        endCal = Calendar.getInstance();
        endCal.add(Calendar.HOUR_OF_DAY, 4);
        updateTypeUi();
        renderTimes();
        loadRecords();
    }

    private void setType(String t) {
        type = t;
        updateTypeUi();
    }

    private void updateTypeUi() {
        boolean leave = "LEAVE".equals(type);
        tvTitle.setText(leave ? "请假申请" : "临时外出");
        btnSubmit.setText(leave ? "提交请假申请" : "提交外出申请");
        tabLeave.setBackground(roundBg(leave ? 0xFF3B6EF6 : 0x00000000, 10));
        tabLeave.setTextColor(leave ? 0xFFFFFFFF : 0xFF6B7280);
        tabLeave.setTypeface(Typeface.DEFAULT, leave ? Typeface.BOLD : Typeface.NORMAL);
        tabOuting.setBackground(roundBg(leave ? 0x00000000 : 0xFF3B6EF6, 10));
        tabOuting.setTextColor(leave ? 0xFF6B7280 : 0xFFFFFFFF);
        tabOuting.setTypeface(Typeface.DEFAULT, leave ? Typeface.NORMAL : Typeface.BOLD);
        tvRuleHint.setText(leave
                ? "请假 3 天以内由班主任审批；超过 3 天需班主任初审后报学生处终审。返校后须定位拍照打卡。"
                : "临时外出原则上当天往返，由班主任审批；返校须定位拍照打卡。");
    }

    private void pickDateTime(boolean isStart) {
        Calendar base = isStart ? startCal : endCal;
        new DatePickerDialog(this, (view, y, m, d) -> {
            base.set(Calendar.YEAR, y);
            base.set(Calendar.MONTH, m);
            base.set(Calendar.DAY_OF_MONTH, d);
            new TimePickerDialog(this, (tv, h, min) -> {
                base.set(Calendar.HOUR_OF_DAY, h);
                base.set(Calendar.MINUTE, min);
                base.set(Calendar.SECOND, 0);
                renderTimes();
            }, base.get(Calendar.HOUR_OF_DAY), base.get(Calendar.MINUTE), true).show();
        }, base.get(Calendar.YEAR), base.get(Calendar.MONTH), base.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void renderTimes() {
        tvStart.setText(FMT.format(startCal.getTime()));
        tvEnd.setText(FMT.format(endCal.getTime()));
        tvStart.setTextColor(0xFF1F2937);
        tvEnd.setTextColor(0xFF1F2937);
    }

    private void submit() {
        String reason = etReason.getText().toString().trim();
        String dest = etDestination.getText().toString().trim();
        String phone = etParentPhone.getText().toString().trim();
        if (reason.isEmpty()) {
            Toast.makeText(this, "请填写事由", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!endCal.after(startCal)) {
            Toast.makeText(this, "回校时间必须晚于出校时间", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!cbParent.isChecked()) {
            Toast.makeText(this, "请先告知家长并勾选确认", Toast.LENGTH_SHORT).show();
            return;
        }
        if (phone.length() < 7) {
            Toast.makeText(this, "请填写家长联系电话", Toast.LENGTH_SHORT).show();
            return;
        }

        btnSubmit.setEnabled(false);
        Map<String, Object> body = new HashMap<>();
        body.put("type", type);
        body.put("reason", reason);
        body.put("destination", dest);
        body.put("startTime", FMT.format(startCal.getTime()));
        body.put("endTime", FMT.format(endCal.getTime()));
        body.put("parentNotified", true);
        body.put("parentPhone", phone);

        ApiService api = RetrofitClient.getInstance().create(ApiService.class);
        api.applyLeave(token, body).enqueue(new Callback<Map<String, Object>>() {
            @Override
            public void onResponse(Call<Map<String, Object>> call, Response<Map<String, Object>> resp) {
                btnSubmit.setEnabled(true);
                if (resp.isSuccessful() && resp.body() != null) {
                    Object msg = resp.body().get("msg");
                    Toast.makeText(LeaveActivity.this, msg == null ? "已提交" : String.valueOf(msg), Toast.LENGTH_LONG).show();
                    etReason.setText("");
                    etDestination.setText("");
                    loadRecords();
                } else {
                    String err = parseError(resp);
                    Toast.makeText(LeaveActivity.this, err, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(Call<Map<String, Object>> call, Throwable t) {
                btnSubmit.setEnabled(true);
                Toast.makeText(LeaveActivity.this, UiMsg.network(t), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private String parseError(Response<?> resp) {
        try {
            String s = resp.errorBody() == null ? "" : resp.errorBody().string();
            int i = s.indexOf("\"error\":\"");
            if (i >= 0) {
                int j = s.indexOf('"', i + 9);
                if (j > i) return s.substring(i + 9, j);
            }
        } catch (Exception ignored) {
        }
        return "提交失败，请稍后再试";
    }

    private void loadRecords() {
        ApiService api = RetrofitClient.getInstance().create(ApiService.class);
        api.getMyLeaves(token).enqueue(new Callback<List<Map<String, Object>>>() {
            @Override
            public void onResponse(Call<List<Map<String, Object>>> call, Response<List<Map<String, Object>>> resp) {
                boxRecords.removeAllViews();
                if (!resp.isSuccessful() || resp.body() == null) return;
                for (Map<String, Object> item : resp.body()) {
                    boxRecords.addView(buildRecord(item));
                }
            }

            @Override
            public void onFailure(Call<List<Map<String, Object>>> call, Throwable t) {
            }
        });
    }

    private LinearLayout buildRecord(Map<String, Object> item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundBg(0xFFFFFFFF, 14, 0xFFEFEFF3));
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(lp);

        String typeText = str(item.get("typeText"));
        String statusText = str(item.get("statusText"));
        String status = str(item.get("status"));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = new TextView(this);
        title.setText(typeText + " · " + str(item.get("startText")));
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF1F2937);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView badge = new TextView(this);
        badge.setText(statusText);
        badge.setTextSize(11);
        badge.setPadding(dp(8), dp(3), dp(8), dp(3));
        badge.setTextColor(statusColor(status));
        badge.setBackground(roundBg(statusBg(status), 8, 0));
        head.addView(title);
        head.addView(badge);
        card.addView(head);

        TextView detail = new TextView(this);
        detail.setText("回校：" + str(item.get("endText")) + "\n事由：" + str(item.get("reason")));
        detail.setTextSize(12);
        detail.setTextColor(0xFF6B7280);
        detail.setLineSpacing(dp(2), 1f);
        detail.setPadding(0, dp(6), 0, dp(8));
        card.addView(detail);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);

        Object idObj = item.get("id");
        long id = idObj instanceof Number ? ((Number) idObj).longValue() : 0;

        if ("PENDING_TEACHER".equals(status) || "PENDING_LEADER".equals(status)) {
            Button cancel = smallBtn("取消申请", 0xFF9CA3AF);
            cancel.setOnClickListener(v -> cancelRequest(id));
            actions.addView(cancel);
        }
        if ("APPROVED".equals(status)) {
            Button ret = smallBtn("返校打卡", 0xFF3B6EF6);
            ret.setOnClickListener(v -> startReturnCheckIn(id));
            actions.addView(ret);
        }
        if ("RETURNED".equals(status)) {
            TextView done = new TextView(this);
            done.setText("已于 " + str(item.get("returnAt")) + " 打卡返校");
            done.setTextSize(11);
            done.setTextColor(0xFF10B981);
            actions.addView(done);
        }
        if (actions.getChildCount() > 0) card.addView(actions);
        return card;
    }

    private Button smallBtn(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setTextColor(0xFFFFFFFF);
        b.setBackground(roundBg(color, 8, 0));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setHeight(dp(34));
        b.setStateListAnimator(null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(8), 0, 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void cancelRequest(long id) {
        ApiService api = RetrofitClient.getInstance().create(ApiService.class);
        api.cancelLeave(token, id).enqueue(new Callback<Map<String, Object>>() {
            @Override
            public void onResponse(Call<Map<String, Object>> call, Response<Map<String, Object>> resp) {
                Toast.makeText(LeaveActivity.this,
                        resp.body() == null ? "已取消" : str(resp.body().get("msg")), Toast.LENGTH_SHORT).show();
                loadRecords();
            }

            @Override
            public void onFailure(Call<Map<String, Object>> call, Throwable t) {
                Toast.makeText(LeaveActivity.this, UiMsg.network(t), Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ===== 返校打卡：拍照 + 定位 =====
    private void startReturnCheckIn(long id) {
        pendingReturnId = id;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivityForResult(intent, REQ_CAMERA);
        } else {
            Toast.makeText(this, "当前设备不支持拍照", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED && pendingReturnId != null) {
            startReturnCheckIn(pendingReturnId);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAMERA || resultCode != RESULT_OK || data == null || pendingReturnId == null) return;
        Object obj = data.getExtras() == null ? null : data.getExtras().get("data");
        if (!(obj instanceof Bitmap)) {
            Toast.makeText(this, "未获取到照片，请重试", Toast.LENGTH_SHORT).show();
            return;
        }
        Bitmap bitmap = (Bitmap) obj;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, bos);
        byte[] bytes = bos.toByteArray();

        RequestBody photoBody = RequestBody.create(bytes, MediaType.parse("image/jpeg"));
        MultipartBody.Part part = MultipartBody.Part.createFormData("photo", "return.jpg", photoBody);
        double[] loc = lastLocation();
        RequestBody latBody = RequestBody.create(String.valueOf(loc == null ? "" : loc[0]), MediaType.parse("text/plain"));
        RequestBody lngBody = RequestBody.create(String.valueOf(loc == null ? "" : loc[1]), MediaType.parse("text/plain"));

        long id = pendingReturnId;
        pendingReturnId = null;
        ApiService api = RetrofitClient.getInstance().create(ApiService.class);
        api.returnLeave(token, id, part, latBody, lngBody).enqueue(new Callback<Map<String, Object>>() {
            @Override
            public void onResponse(Call<Map<String, Object>> call, Response<Map<String, Object>> resp) {
                if (resp.isSuccessful()) {
                    Toast.makeText(LeaveActivity.this, "返校打卡成功", Toast.LENGTH_SHORT).show();
                    loadRecords();
                } else {
                    Toast.makeText(LeaveActivity.this, parseError(resp), Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(Call<Map<String, Object>> call, Throwable t) {
                Toast.makeText(LeaveActivity.this, UiMsg.network(t), Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 取最近一次定位（无权限或失败返回 null，后端允许为空） */
    private double[] lastLocation() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                return null;
            }
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (lm == null) return null;
            Location best = null;
            for (String p : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER}) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            return best == null ? null : new double[]{best.getLatitude(), best.getLongitude()};
        } catch (Exception e) {
            return null;
        }
    }

    private int statusColor(String status) {
        return switch (status) {
            case "APPROVED", "RETURNED" -> 0xFF10B981;
            case "REJECTED" -> 0xFFEF4444;
            case "CANCELED" -> 0xFF9CA3AF;
            default -> 0xFF3B6EF6;
        };
    }

    private int statusBg(String status) {
        return switch (status) {
            case "APPROVED", "RETURNED" -> 0xFFECFDF5;
            case "REJECTED" -> 0xFFFEF2F2;
            case "CANCELED" -> 0xFFF3F4F6;
            default -> 0xFFEFF4FF;
        };
    }

    private GradientDrawable roundBg(int color, int radiusDp) {
        return roundBg(color, radiusDp, 0);
    }

    private GradientDrawable roundBg(int color, int radiusDp, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) d.setStroke(dp(1), strokeColor);
        return d;
    }

    private String str(Object o) {
        return o == null ? "-" : String.valueOf(o);
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
