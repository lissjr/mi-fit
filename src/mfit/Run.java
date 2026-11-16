package com.hanxing.mifit;

import org.json.JSONObject;

/** 一场运动记录（来自 sport_report 的 JSON） */
public class Run {
    public long start, end;
    public int timer, dist, cal, steps;
    public String key, category, sid;
    public double avgHr, maxHr, avgCad, maxCad;
    public double ascent, descent, avgAlt, minAlt, maxAlt, pace;
    public double stride, ct, vamp, vsr;
    public JSONObject rep;
    public long trackTs = -1;      // 匹配到的轨迹文件时间戳；-1 表示没有
    public int trackProto;
    public String recPath, gpsPath;   // 逐点文件绝对路径

    public boolean hasTrack() { return trackTs >= 0; }

    public static final java.util.Map<String, String> LABEL = new java.util.HashMap<String, String>();
    static {
        String[][] m = {
            {"outdoor_running", "户外跑步"}, {"indoor_running", "室内跑步"},
            {"treadmill_running", "跑步机"}, {"trail_running", "越野跑"},
            {"playground_running", "操场跑步"}, {"free_running", "自由跑步"},
            {"outdoor_riding", "户外骑行"}, {"indoor_riding", "室内骑行"},
            {"spinning", "动感单车"}, {"walking", "步行"},
            {"outdoor_walking", "户外步行"}, {"indoor_walking", "室内步行"},
            {"hiking", "徒步"}, {"climbing", "爬山"}, {"mountaineering", "登山"},
            {"strength_training", "力量训练"}, {"physical_training", "体能训练"},
            {"free_training", "自由训练"}, {"high_interval_training", "高强度间歇"},
            {"core_training", "核心训练"}, {"step_training", "台阶训练"},
            {"indoor_fitness", "室内健身"}, {"taichi", "太极"}, {"rope_skipping", "跳绳"},
            {"rowing_machine", "划船机"}, {"elliptical", "椭圆机"}, {"yoga", "瑜伽"},
            {"stretching", "拉伸"}, {"dance", "舞蹈"}, {"boxing", "拳击"},
            {"basketball", "篮球"}, {"football", "足球"}, {"volleyball", "排球"},
            {"badminton", "羽毛球"}, {"pingpong", "乒乓球"}, {"tennis", "网球"},
            {"golf", "高尔夫"}, {"swimming", "游泳"}, {"skiing", "滑雪"},
            {"parallel_skiing", "平行滑雪"}, {"snowboarding", "单板滑雪"},
            {"skate", "滑板"}, {"archery", "射箭"}, {"fencing", "击剑"},
            {"martial_arts", "武术"}, {"rock_climbing", "攀岩"},
            {"e_sports", "电子竞技"}, {"esports", "电子竞技"}, {"esport", "电子竞技"},
            {"motion_sensing_game", "体感游戏"}
        };
        for (String[] p : m) LABEL.put(p[0], p[1]);
    }

    public String label() {
        String s = LABEL.get(key);
        if (s == null) s = LABEL.get(category);
        if (s != null) return s;
        if (key == null || key.length() == 0) return "运动";
        // 兜底：不再直接暴露英文原始 key
        String t = key.replace('_', ' ');
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    public boolean isRide() { return key != null && key.contains("riding"); }
    public boolean isRun() { return key != null && key.contains("running"); }
    public boolean isWalk() { return key != null && key.contains("walking"); }

    public static Run from(java.util.Map<String, Object> row) {
        Object v = row.get("value");
        if (!(v instanceof String)) return null;
        try {
            JSONObject o = new JSONObject((String) v);
            if (!o.has("start_time")) return null;
            Run r = new Run();
            r.rep = o;
            r.key = (String) row.get("key");
            r.category = (String) row.get("category");
            r.sid = (String) row.get("sid");
            r.start = o.optLong("start_time");
            r.timer = (int) o.optDouble("duration", 0);
            r.end = o.has("end_time") ? o.optLong("end_time") : r.start + r.timer;
            r.dist = (int) o.optDouble("distance", 0);
            r.cal = (int) o.optDouble("calories", 0);
            r.steps = (int) o.optDouble("steps", 0);
            r.avgHr = o.optDouble("avg_hrm", 0);
            r.maxHr = o.optDouble("max_hrm", 0);
            r.avgCad = o.optDouble("avg_cadence", 0);
            r.maxCad = o.optDouble("max_cadence", 0);
            r.ascent = o.optDouble("rise_height", 0);
            r.descent = o.optDouble("fall_height", 0);
            r.avgAlt = o.optDouble("avg_height", 0);
            r.minAlt = o.optDouble("min_height", 0);
            r.maxAlt = o.optDouble("max_height", 0);
            r.stride = o.optDouble("avg_stride", 0);
            r.ct = o.optDouble("avg_touchdown_duration", 0);
            r.vamp = o.optDouble("avg_vertical_amplitude", 0);
            r.vsr = o.optDouble("avg_vertical_stride_ratio", 0);
            r.pace = o.optDouble("avg_pace", 0);
            if (r.pace <= 0 && r.dist > 0 && r.timer > 0) r.pace = r.timer / (r.dist / 1000.0);
            return r;
        } catch (Exception e) {
            return null;
        }
    }
}
