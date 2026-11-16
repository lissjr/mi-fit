package com.hanxing.mifit;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** 生理数据（心率/睡眠/血氧/压力/步数/体重/最大摄氧量/训练负荷/成绩预测） */
public class Health {
    // 今日心率
    public final List<double[]> hr = new ArrayList<double[]>();      // {ts, bpm}
    public int hrNow, hrMin, hrMax, hrAvg;
    // 血氧
    public final List<double[]> spo2 = new ArrayList<double[]>();    // {ts, %}
    public int spo2Now, spo2Min, spo2Avg;
    // 步数
    public int steps, stepDistM, stepCal;
    // 压力 / 站立
    public int stressNow, stressAvg, standHours;
    // 睡眠
    public boolean hasSleep;
    public long sleepEnd;
    public int sleepTotal, deepMin, lightMin, remMin, awakeMin, sleepEff;
    public int sleepHr, sleepHrv, sleepSpo2, sleepBreath, sleepWakeCount;
    public final List<double[]> hrvTrend = new ArrayList<double[]>();  // 近 N 晚 {醒来时刻, HRV}
    public final List<int[]> stages = new ArrayList<int[]>();          // {state, minutes}
    // 体重
    public boolean hasWeight;
    public long weightTs;
    public double kg, bodyFat, bmiVal, muscle;
    public int bmr;
    public final List<double[]> weightTrend = new ArrayList<double[]>(); // {ts, kg}
    public final List<double[]> weightAll = new ArrayList<double[]>();   // 全量称重，供按时间取体重
    // 能力
    public int vo2max, vo2level;
    public int loadWeek, loadLevel, loadToday, loadDaysWithValue;
    public final List<double[]> loadTrend = new ArrayList<double[]>();   // {dayTs, load}
    public double ability;
    public int abilityLevel;
    public int p5k, p10k, pHalf, pFull;
    public int vitality;
    public String err;

    /** 从库里读（每张表单独 try，某张表出错不影响其它） */
    public static Health load(Db db) {
        Health h = new Health();
        long now = System.currentTimeMillis() / 1000L;
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        long today = c.getTimeInMillis() / 1000L;

        // ---- 心率（1 分钟一条，读尾部两天）----
        try {
            List<Map<String, Object>> rows = db.readTail("hr_record", 1100);
            for (Map<String, Object> row : rows) {
                Object v = row.get("value");
                if (!(v instanceof String)) continue;
                JSONObject o = new JSONObject((String) v);
                double bpm = o.optDouble("bpm", 0);
                long ts = o.optLong("time", 0);
                if (bpm > 0 && ts >= today - 3600) h.hr.add(new double[]{ts, bpm});
            }
            Collections.sort(h.hr, new Comparator<double[]>() {
                public int compare(double[] a, double[] b) { return Double.compare(a[0], b[0]); }
            });
            int mn = 999, mx = 0;
            double sum = 0;
            for (double[] p : h.hr) {
                if (p[0] < today) continue;
                int v = (int) p[1];
                mn = Math.min(mn, v); mx = Math.max(mx, v); sum += v;
            }
            if (sum > 0) {
                h.hrMin = mn; h.hrMax = mx;
                h.hrAvg = (int) Math.round(sum / Math.max(1, countToday(h.hr, today)));
                h.hrNow = (int) h.hr.get(h.hr.size() - 1)[1];
            } else {
                h.hrMin = 0;
            }
        } catch (Exception e) { h.err = "心率 " + e.getMessage(); }

        // ---- 血氧 ----
        try {
            List<Map<String, Object>> rows = db.readTail("spo2_record", 700);
            for (Map<String, Object> row : rows) {
                Object v = row.get("value");
                if (!(v instanceof String)) continue;
                JSONObject o = new JSONObject((String) v);
                double s = o.optDouble("spo2", 0);
                long ts = o.optLong("time", 0);
                if (s > 0 && ts >= today - 3600) h.spo2.add(new double[]{ts, s});
            }
            Collections.sort(h.spo2, new Comparator<double[]>() {
                public int compare(double[] a, double[] b) { return Double.compare(a[0], b[0]); }
            });
            int mn = 999; double sum = 0; int n = 0;
            for (double[] p : h.spo2) {
                if (p[0] < today) continue;
                mn = Math.min(mn, (int) p[1]); sum += p[1]; n++;
            }
            if (n > 0) {
                h.spo2Min = mn; h.spo2Avg = (int) Math.round(sum / n);
                h.spo2Now = (int) h.spo2.get(h.spo2.size() - 1)[1];
            } else h.spo2Min = 0;
        } catch (Exception e) { h.err = "血氧 " + e.getMessage(); }

        // ---- 步数 / 卡路里（同一分钟常有两条来源，取较大值，避免重复累加）----
        try {
            List<Map<String, Object>> rows = db.readTail("step_record", 1500);
            HashMap<Long, int[]> perMin = new HashMap<Long, int[]>();
            for (Map<String, Object> row : rows) {
                Object v = row.get("value");
                if (!(v instanceof String)) continue;
                JSONObject o = new JSONObject((String) v);
                long ts = o.optLong("time", 0);
                if (ts < today) continue;
                int st = (int) o.optDouble("steps", 0);
                int dm = (int) o.optDouble("distance", 0);
                int cl = (int) o.optDouble("calories", 0);
                int[] cur = perMin.get(ts);
                if (cur == null) perMin.put(ts, new int[]{st, dm, cl});
                else if (st > cur[0]) { cur[0] = st; cur[1] = dm; cur[2] = cl; }
            }
            for (int[] p : perMin.values()) {
                h.steps += p[0]; h.stepDistM += p[1]; h.stepCal += p[2];
            }
        } catch (Exception e) { h.err = "步数 " + e.getMessage(); }

        // ---- 压力 / 站立 ----
        try {
            List<Map<String, Object>> rows = db.readTail("stress_record", 600);
            double sum = 0; int n = 0;
            for (Map<String, Object> row : rows) {
                Object v = row.get("value");
                if (!(v instanceof String)) continue;
                JSONObject o = new JSONObject((String) v);
                long ts = o.optLong("time", 0);
                if (ts >= today) { sum += o.optDouble("stress", 0); n++; }
            }
            if (n > 0) h.stressAvg = (int) Math.round(sum / n);
            if (!rows.isEmpty()) {
                JSONObject o = new JSONObject((String) rows.get(0).get("value"));
                h.stressNow = (int) o.optDouble("stress", 0);
            }
        } catch (Exception e) { h.err = "压力 " + e.getMessage(); }
        try {
            List<Map<String, Object>> rows = db.readTail("stand_record", 80);
            HashSet<Integer> hours = new HashSet<Integer>();
            for (Map<String, Object> row : rows) {
                Object v = row.get("value");
                if (!(v instanceof String)) continue;
                JSONObject o = new JSONObject((String) v);
                long st = o.optLong("start_time", 0);
                if (st >= today && st < today + 86400) {
                    hours.add((int) ((st + 8L * 3600) / 3600 % 24));
                }
            }
            h.standHours = hours.size();
        } catch (Exception e) { h.err = "站立 " + e.getMessage(); }

        // ---- 睡眠（最近一条出分期，近若干条出 HRV 趋势）----
        try {
            List<Map<String, Object>> rows = db.readTail("sleep_segment", 40);
            boolean got = false;
            HashSet<Long> days = new HashSet<Long>();
            for (Map<String, Object> row : rows) {
                Object v = row.get("value");
                if (!(v instanceof String)) continue;
                JSONObject o = new JSONObject((String) v);
                int deep = o.optInt("sleep_deep_duration", 0);
                int light = o.optInt("sleep_light_duration", 0);
                int rem = o.optInt("sleep_rem_duration", -1);
                if (deep == 0 && light == 0) continue;      // 未分级的半条记录
                int hrv = o.optInt("avg_hrv", 0);
                long wake = o.optLong("wake_up_time", o.optLong("time", 0));
                if (hrv > 0 && wake > 0) {
                    long day = (wake + 8L * 3600) / 86400L;
                    if (!days.contains(day)) {
                        days.add(day);
                        h.hrvTrend.add(new double[]{wake, hrv});
                    }
                }
                if (got) continue;
                got = true;
                h.hasSleep = true;
                h.sleepTotal = o.optInt("duration", 0);
                h.deepMin = deep; h.lightMin = light; h.remMin = Math.max(0, rem);
                h.awakeMin = o.optInt("sleep_awake_duration", 0);
                h.sleepEff = o.optInt("sleep_efficiency", 0);
                h.sleepEnd = o.optLong("wake_up_time", o.optLong("time", 0));
                h.sleepHr = o.optInt("avg_hr", 0);
                h.sleepHrv = hrv;
                h.sleepSpo2 = o.optInt("avg_spo2", 0);
                h.sleepBreath = o.optInt("avg_breath", 0);
                h.sleepWakeCount = o.optInt("awake_count", 0);
                JSONArray items = o.optJSONArray("items");
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject it = items.optJSONObject(i);
                        if (it == null) continue;
                        long s = it.optLong("start_time", 0), e = it.optLong("end_time", 0);
                        int st = it.optInt("state", 0);
                        int min = (int) Math.round((e - s) / 60.0);
                        if (min > 0) h.stages.add(new int[]{st, min});
                    }
                }
            }
        } catch (Exception e) { h.err = "睡眠 " + e.getMessage(); }

        // ---- 体重 ----
        try {
            List<Map<String, Object>> rows = db.readTableObjects("weight_item_record");
            List<double[]> list = new ArrayList<double[]>();
            for (Map<String, Object> row : rows) {
                Object w = row.get("weight");
                if (!(w instanceof Double) && !(w instanceof Long)) continue;
                double kgv = ((Number) w).doubleValue();
                Object t = row.get("time");
                long ts = t instanceof Long ? ((Long) t).longValue() : 0;
                if (kgv <= 0 || ts <= 0) continue;
                list.add(new double[]{ts, kgv});
                if (row.get("bodyFatRate") instanceof Double) {
                    h.bodyFat = ((Double) row.get("bodyFatRate"));
                }
            }
            Collections.sort(list, new Comparator<double[]>() {
                public int compare(double[] a, double[] b) { return Double.compare(a[0], b[0]); }
            });
            if (!list.isEmpty()) {
                h.hasWeight = true;
                double[] last = list.get(list.size() - 1);
                h.kg = last[1]; h.weightTs = (long) last[0];
                // 找最近一条带完整成分的记录
                for (int i = rows.size() - 1; i >= 0; i--) {
                    Map<String, Object> row = rows.get(i);
                    if (!(row.get("weight") instanceof Double)) continue;
                    Object to = row.get("time");
                    long ts = to instanceof Long ? ((Long) to).longValue() : 0L;
                    if (Math.abs(ts - h.weightTs) > 1) continue;
                    if (row.get("bodyFatRate") instanceof Double) h.bodyFat = (Double) row.get("bodyFatRate");
                    if (row.get("bmi") instanceof Double) h.bmiVal = (Double) row.get("bmi");
                    if (row.get("muscleMass") instanceof Double) h.muscle = (Double) row.get("muscleMass");
                    if (row.get("basalMetabolism") instanceof Long) h.bmr = (int) ((Long) row.get("basalMetabolism")).longValue();
                    break;
                }
                int from = Math.max(0, list.size() - 12);
                for (int i = from; i < list.size(); i++) h.weightTrend.add(list.get(i));
                h.weightAll.addAll(list);
            }
        } catch (Exception e) { h.err = "体重 " + e.getMessage(); }

        // ---- 最大摄氧量 ----
        try {
            List<Map<String, Object>> rows = db.readTail("v02max_record", 12);
            long best = 0;
            for (Map<String, Object> row : rows) {
                Object v = row.get("value");
                if (!(v instanceof String)) continue;
                JSONObject o = new JSONObject((String) v);
                long ts = o.optLong("time", 0);
                if (ts <= best) continue;
                int val = o.optInt("vo2_max", 0);
                if (val <= 0) continue;
                best = ts;
                h.vo2max = val;
                h.vo2level = o.optInt("vo2_max_level", 0);
            }
        } catch (Exception e) { h.err = "摄氧量 " + e.getMessage(); }

        // ---- 训练负荷 ----
        try {
            List<Map<String, Object>> rows = db.readTail("training_load_record", 60);
            List<double[]> list = new ArrayList<double[]>();
            long best = 0;
            for (Map<String, Object> row : rows) {
                Object v = row.get("value");
                if (!(v instanceof String)) continue;
                JSONObject o = new JSONObject((String) v);
                long ts = o.optLong("date_time", 0);
                double day = o.optDouble("current_day_train_load", -1);
                if (ts > 0 && day >= 0) list.add(new double[]{ts, day});
                if (ts > best) {
                    best = ts;
                    h.loadWeek = o.optInt("wtl_sum", 0);
                    h.loadLevel = o.optInt("current_train_load_level", 0);
                    if (day >= 0) h.loadToday = (int) day;
                }
            }
            Collections.sort(list, new Comparator<double[]>() {
                public int compare(double[] a, double[] b) { return Double.compare(a[0], b[0]); }
            });
            // 按天归并：最近 14 天每天一根柱（缺的天补 0），柱子才和横轴时间对应
            HashMap<Long, Double> byDay = new HashMap<Long, Double>();
            for (double[] p : list) {
                long dayTs = ((long) p[0] + 8 * 3600L) / 86400L * 86400L - 8 * 3600L;
                byDay.put(dayTs, p[1]);
            }
            for (int i = 13; i >= 0; i--) {
                long dayTs = today - i * 86400L;
                Double v = byDay.get(dayTs);
                h.loadTrend.add(new double[]{dayTs, v == null ? 0 : v.doubleValue()});
                if (v != null && v > 0) h.loadDaysWithValue++;
            }
        } catch (Exception e) { h.err = "训练负荷 " + e.getMessage(); }

        // ---- 跑步能力 / 成绩预测 ----
        try {
            List<Map<String, Object>> rows = db.readTail("running_ability_record", 30);
            long bestA = 0, bestG = 0;
            for (Map<String, Object> row : rows) {
                Object k = row.get("key");
                Object v = row.get("value");
                if (!(v instanceof String)) continue;
                JSONObject o = new JSONObject((String) v);
                long ts = o.optLong("time", 0);
                if ("running_ability_index".equals(k) && ts > bestA) {
                    bestA = ts;
                    h.ability = o.optDouble("running_ability_index", 0);
                    h.abilityLevel = o.optInt("running_ability_level", 0);
                } else if ("grade_prediction".equals(k) && ts > bestG) {
                    bestG = ts;
                    h.p5k = o.optInt("five_kilometre_grade_prediction_duration", 0);
                    h.p10k = o.optInt("ten_kilometre_grade_prediction_duration", 0);
                    h.pHalf = o.optInt("half_marathon_grade_prediction_duration", 0);
                    h.pFull = o.optInt("full_marathon_grade_prediction_duration", 0);
                }
            }
        } catch (Exception e) { h.err = "跑步能力 " + e.getMessage(); }

        // ---- 活力值 ----
        try {
            List<Map<String, Object>> rows = db.readTail("vitality_record", 3);
            if (!rows.isEmpty()) {
                JSONObject o = new JSONObject((String) rows.get(0).get("value"));
                h.vitality = o.optInt("latest_accumulated_vitality", 0);
            }
        } catch (Exception e) { /* 忽略 */ }

        return h;
    }

    private static int countToday(List<double[]> list, long today) {
        int n = 0;
        for (double[] p : list) if (p[0] >= today) n++;
        return n;
    }
}
