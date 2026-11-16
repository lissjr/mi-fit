package com.hanxing.mifit;

/** 按系统蓝牙里读到的设备名匹配内置官方产品图；认不出的返回 0，交给自绘插画兜底 */
public class DeviceArt {

    public static int of(String name) {
        if (name == null) return 0;
        String s = name.toLowerCase();
        if (s.contains("redmi watch")) {
            if (s.contains("5")) return R.drawable.device_redmi_watch5;
            return 0;
        }
        if (s.contains("watch")) {
            if (s.contains("s5")) return R.drawable.device_watch_s5;
            if (s.contains("s4")) return R.drawable.device_watch_s4;
            if (s.contains("s3")) return R.drawable.device_watch_s3;
            return 0;
        }
        if (s.contains("band") || s.contains("手环")) {
            if (s.contains("9")) return R.drawable.device_band9;
            return 0;
        }
        return 0;
    }
}
