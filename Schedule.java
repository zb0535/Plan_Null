import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 개별 일정의 정보를 담는 데이터(VO) 객체 클래스
 * 내부적으로 남은 일수를 계산하는 D-Day 로직과 알림 시각 계산 로직을 포함
 *
 * <p>content 는 "제목 | 항목: 값 | 항목: 값" 형태의 한 줄 문자열이다.
 * {@link #getTitle()}, {@link #getDetails()} 로 꺼내고 {@link #buildContent(String, Map)} 로 만든다.
 * 알림은 "시간" 항목에 적힌 시각을 기준으로, 그보다 alertHours 시간 전에 울린다.</p>
 *
 * @author 정현우
 * @version 1.0
 */
public class Schedule {
    /** 알림 기준 시각을 읽어 오는 입력 항목 이름 */
    public static final String TIME_KEY = "시간";

    private static final String SEPARATOR = " | ";
    private static final Pattern DATE_PATTERN =
            Pattern.compile("(\\d{4})[-./](\\d{1,2})[-./](\\d{1,2})|(\\d{4})(\\d{2})(\\d{2})");
    private static final Pattern TIME_PATTERN =
            Pattern.compile("(오전|오후|AM|PM|am|pm)?\\s*(\\d{1,2})\\s*(?::|시)\\s*(\\d{1,2})?");

    private final String scheduleID;
    private String targetDate;
    private String tag;
    private String content;
    private boolean isExcluded;
    private boolean isCompleted;
    private int alertHours;      // 일정 시각의 몇 시간 전에 알릴지 (0 이면 알림 없음)
    private boolean alerted;     // 알림을 이미 울렸는지

    /** 새 일정을 만든다. (ID 는 자동 생성) */
    public Schedule(String targetDate, String tag, String content, boolean isExcluded) {
        this(UUID.randomUUID().toString(), targetDate, tag, content, isExcluded, false, 0, false);
    }

    /** 알림 정보가 없던 옛 형식으로 저장된 일정을 복원한다. */
    public Schedule(String id, String date, String tag, String content, boolean excluded, boolean completed) {
        this(id, date, tag, content, excluded, completed, 0, false);
    }

    /** 모든 값을 지정해 일정을 복원한다. */
    public Schedule(String id, String date, String tag, String content, boolean excluded,
                    boolean completed, int alertHours, boolean alerted) {
        this.scheduleID = id;
        this.targetDate = date;
        this.tag = tag;
        this.content = content == null ? "" : content;
        this.isExcluded = excluded;
        this.isCompleted = completed;
        this.alertHours = Math.max(0, alertHours);
        this.alerted = alerted;
    }

    // ===== 날짜 · 시각 =====

    /** "YYYY-MM-DD", "YYYY.MM.DD", "YYYY/MM/DD", "YYYYMMDD" 를 "YYYY-MM-DD" 로 통일한다. 올바르지 않으면 null */
    public static String normalizeDate(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = DATE_PATTERN.matcher(text.replaceAll("\\s", ""));
        if (!m.matches()) {
            return null;
        }
        boolean separated = m.group(1) != null;
        try {
            return LocalDate.of(Integer.parseInt(separated ? m.group(1) : m.group(4)),
                    Integer.parseInt(separated ? m.group(2) : m.group(5)),
                    Integer.parseInt(separated ? m.group(3) : m.group(6))).toString();
        } catch (DateTimeException e) {
            return null;
        }
    }

    /** 문장 속 첫 번째 시각을 찾는다. "14:30", "12:00~16:00"(→12:00), "~23:59", "오후 2시", "9시 30분" 등. 없으면 null */
    public static LocalTime parseTime(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = TIME_PATTERN.matcher(text);
        while (m.find()) {
            int hour = Integer.parseInt(m.group(2));
            int minute = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
            String ampm = m.group(1);
            if (ampm != null) {
                if (hour < 1 || hour > 12) {
                    continue;
                }
                boolean pm = ampm.equals("오후") || ampm.equalsIgnoreCase("PM");
                hour = hour % 12 + (pm ? 12 : 0);
            }
            if (hour <= 23 && minute <= 59) {
                return LocalTime.of(hour, minute);
            }
        }
        return null;
    }

    /** 오늘 기준 남은 일수. 오늘이면 0, 지난 일정은 음수 */
    public int calculateDDay() {
        try {
            return (int) ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(targetDate));
        } catch (RuntimeException e) {
            return 0; // 날짜 포맷 오류 시 0 반환
        }
    }

    /** "시간" 항목까지 반영한 일정 시각. 시간 항목이 없거나 읽을 수 없으면 null */
    public LocalDateTime getEventDateTime() {
        LocalTime time = parseTime(getDetail(TIME_KEY));
        if (time == null) {
            return null;
        }
        try {
            return LocalDate.parse(targetDate).atTime(time);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 알림이 울릴 시각(일정 시각 - alertHours). 알림을 쓰지 않으면 null */
    public LocalDateTime getAlertDateTime() {
        LocalDateTime event = getEventDateTime();
        return (alertHours > 0 && event != null) ? event.minusHours(alertHours) : null;
    }

    // ===== 내용(content) 해석 =====

    /** 제목과 항목들을 "제목 | 항목: 값 | ..." 형태의 content 로 합친다. 값이 빈 항목은 넣지 않는다. */
    public static String buildContent(String title, Map<String, String> details) {
        StringBuilder sb = new StringBuilder(clean(title));
        for (Map.Entry<String, String> e : details.entrySet()) {
            String value = clean(e.getValue());
            if (!value.isEmpty()) {
                sb.append(SEPARATOR).append(clean(e.getKey()).replace(":", "")).append(": ").append(value);
            }
        }
        return sb.toString();
    }

    private static String clean(String text) {
        return text == null ? "" : text.replace('|', '/').replaceAll("[\\r\\n\\t]+", " ").trim();
    }

    /** content 의 첫 부분(제목) */
    public String getTitle() {
        return content.split("\\|", -1)[0].trim();
    }

    /** content 의 "항목: 값" 들 (입력한 순서 유지). 옛 형식으로 저장돼 ':' 가 없는 조각은 "메모" 로 본다. */
    public Map<String, String> getDetails() {
        Map<String, String> details = new LinkedHashMap<>();
        String[] parts = content.split("\\|", -1);
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.isEmpty()) {
                continue;
            }
            int colon = part.indexOf(':');
            if (colon > 0) {
                details.put(part.substring(0, colon).trim(), part.substring(colon + 1).trim());
            } else {
                details.put("메모", part);
            }
        }
        return details;
    }

    /** 항목 하나의 값. 없으면 빈 문자열 */
    public String getDetail(String key) {
        String value = getDetails().get(key);
        return value == null ? "" : value;
    }

    // ===== getter / setter =====

    public String getScheduleID() { return scheduleID; }
    public String getTargetDate() { return targetDate; }
    public String getTag() { return tag; }
    public String getContent() { return content; }
    public boolean isExcluded() { return isExcluded; }
    public boolean isCompleted() { return isCompleted; }
    public int getAlertHours() { return alertHours; }
    public boolean isAlerted() { return alerted; }

    public void setTargetDate(String targetDate) { this.targetDate = targetDate; }
    public void setTag(String tag) { this.tag = tag; }
    public void setContent(String content) { this.content = content == null ? "" : content; }
    public void setExcluded(boolean excluded) { isExcluded = excluded; }
    public void setCompleted(boolean completed) { isCompleted = completed; }
    public void setAlertHours(int alertHours) { this.alertHours = Math.max(0, alertHours); }
    public void setAlerted(boolean alerted) { this.alerted = alerted; }
}