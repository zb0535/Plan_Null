import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 시스템의 핵심 비즈니스 로직을 제어하는 매니저 클래스
 *
 * <p>일정이 추가·변경·삭제·완료 처리될 때마다 별도 스레드(백그라운드)에서 CSV 로 자동 저장한다.
 * 따라서 화면(UI)은 파일 저장을 기다리지 않는다. 프로그램을 끝내기 전에는 {@link #shutdown()} 을 호출해
 * 저장 대기 중인 작업을 마무리해야 한다.</p>
 *
 * <p>태그(이름 + 입력 항목 목록)는 사용자가 직접 추가·수정·삭제할 수 있고 태그 파일에 함께 저장된다.
 * 일정마다 "일정 시각의 몇 시간 전" 알림을 줄 수 있으며, 알림 시각이 된 일정은
 * {@link #collectDueAlerts(LocalDateTime)} 로 꺼내 쓴다.</p>
 *
 * @author 정현우
 * @version 1.0
 */
public class ScheduleManager {
    private static final String INVALID_NAME_CHARS = ",;|:=\"\t\r\n";   // 저장 형식에 쓰이는 문자는 이름에 쓸 수 없다
    private static final int MAX_NAME_LENGTH = 20;

    private final List<Schedule> scheduleList;
    private final Map<String, List<String>> tagFields = new LinkedHashMap<>();   // 태그 이름 -> 입력 항목들 (첫 항목이 제목)
    private final FileManager fileManager;
    private final ExecutorService saveExecutor;          // 백그라운드 자동 저장용 (한 줄로 순서대로 처리)
    private volatile Consumer<String> saveFailureListener;

    public ScheduleManager() {
        this(new FileManager());
    }

    public ScheduleManager(FileManager fileManager) {
        this.fileManager = fileManager;
        this.scheduleList = new ArrayList<>(fileManager.readInitialData());
        this.tagFields.putAll(fileManager.readTags());
        if (tagFields.isEmpty()) {
            tagFields.putAll(defaultTags());
        }
        this.saveExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "schedule-saver");
            t.setDaemon(true);
            return t;
        });
    }

    /** 처음 실행할 때 쓰는 기본 태그와 태그별 입력 항목 (첫 항목이 일정 제목이 된다) */
    public static Map<String, List<String>> defaultTags() {
        Map<String, List<String>> tags = new LinkedHashMap<>();
        tags.put("과제", Arrays.asList("과목", "시간", "준비물"));
        tags.put("시험", Arrays.asList("과목", "장소", "시간", "준비물", "범위"));
        tags.put("제출", Arrays.asList("제목", "장소", "시간", "준비물"));
        tags.put("약속", Arrays.asList("제목", "장소", "시간"));
        tags.put("개인", Arrays.asList("제목", "장소", "시간"));
        tags.put("기타", Arrays.asList("제목", "시간"));
        return tags;
    }

    // ===== 일정 =====

    /**
     * 새 일정을 등록하고 자동 저장을 요청한다. (알림 없음)
     *
     * @return 날짜 형식이 올바르고 내용이 있어 일정이 등록되면 true (파일 저장은 백그라운드에서 이어서 진행)
     */
    public synchronized boolean addSchedule(String targetDate, String tag, String content, boolean isExcluded) {
        return addSchedule(targetDate, tag, content, isExcluded, 0);
    }

    /**
     * 새 일정을 등록하고 자동 저장을 요청한다.
     *
     * @param alertHours 일정 시각의 몇 시간 전에 알릴지 (0 이면 알림 없음)
     * @return 날짜 형식이 올바르고 내용이 있어 일정이 등록되면 true
     */
    public synchronized boolean addSchedule(String targetDate, String tag, String content,
                                            boolean isExcluded, int alertHours) {
        String date = Schedule.normalizeDate(targetDate);
        if (date == null || content == null || content.trim().isEmpty()) {
            return false;
        }
        Schedule schedule = new Schedule(date, tag == null ? "기타" : tag, content.trim(), isExcluded);
        schedule.setAlertHours(alertHours);
        scheduleList.add(schedule);
        saveInBackground();
        return true;
    }

    /**
     * 기존 일정의 날짜·태그·내용을 수정한다. 알림 설정은 그대로 둔다. (화면 A 의 [일정 변경])
     *
     * @return 대상 일정이 있고 입력값이 올바르면 true
     */
    public synchronized boolean updateSchedule(String scheduleID, String targetDate, String tag,
                                               String content, boolean isExcluded) {
        for (Schedule s : scheduleList) {
            if (s.getScheduleID().equals(scheduleID)) {
                return updateSchedule(scheduleID, targetDate, tag, content, isExcluded, s.getAlertHours());
            }
        }
        return false;
    }

    /**
     * 기존 일정의 날짜·태그·내용·알림을 수정한다. 알림 시각이 바뀌면 알림을 다시 울릴 수 있게 초기화한다.
     *
     * @param alertHours 일정 시각의 몇 시간 전에 알릴지 (0 이면 알림 없음)
     * @return 대상 일정이 있고 입력값이 올바르면 true
     */
    public synchronized boolean updateSchedule(String scheduleID, String targetDate, String tag,
                                               String content, boolean isExcluded, int alertHours) {
        String date = Schedule.normalizeDate(targetDate);
        if (date == null || content == null || content.trim().isEmpty()) {
            return false;
        }
        for (Schedule s : scheduleList) {
            if (s.getScheduleID().equals(scheduleID)) {
                LocalDateTime alertBefore = s.getAlertDateTime();
                s.setTargetDate(date);
                s.setTag(tag == null ? "기타" : tag);
                s.setContent(content.trim());
                s.setExcluded(isExcluded);
                s.setAlertHours(alertHours);
                if (!Objects.equals(alertBefore, s.getAlertDateTime())) {
                    s.setAlerted(false);   // 알림 시각이 달라졌으니 새로 알릴 수 있게 한다
                }
                saveInBackground();
                return true;
            }
        }
        return false;
    }

    /**
     * @return 해당 ID 의 일정을 찾아 삭제했으면 true
     */
    public synchronized boolean deleteSchedule(String scheduleID) {
        boolean removed = scheduleList.removeIf(s -> s.getScheduleID().equals(scheduleID));
        if (removed) {
            saveInBackground();
        }
        return removed;
    }

    /**
     * 완료 체크 상태를 바꾸고 자동 저장을 요청한다.
     *
     * @return 해당 ID 의 일정을 찾았으면 true
     */
    public synchronized boolean updateCompletionStatus(String scheduleID, boolean isCompleted) {
        for (Schedule s : scheduleList) {
            if (s.getScheduleID().equals(scheduleID)) {
                s.setCompleted(isCompleted);
                saveInBackground();
                return true;
            }
        }
        return false;
    }

    /**
     * D-Day 가 적게 남은 순서(오름차순)로 정렬한다. 같은 D-Day 끼리는 등록 순서를 유지한다.
     *
     * @return 화면에 보여줄 정렬된 일정 목록 (복사본)
     */
    public synchronized List<Schedule> sortSchedulesByDDay() {
        scheduleList.sort(Comparator.comparingInt(Schedule::calculateDDay));
        return new ArrayList<>(scheduleList);
    }

    /**
     * @return 전체 일정 대비 완료된 일정의 비율(0~100, 소수점 버림). 일정이 없으면 0
     */
    public synchronized int calculateProgressRate() {
        if (scheduleList.isEmpty()) return 0;
        long completedCount = scheduleList.stream().filter(Schedule::isCompleted).count();
        return (int) ((completedCount * 100) / scheduleList.size());
    }

    // ===== 알림 =====

    /**
     * 알림 시각(일정 시각 - 알림 시간)이 된 일정을 모아 돌려주고, 같은 알림이 반복되지 않도록 '알림 울림' 으로 표시한다.
     * 완료한 일정은 건너뛰고, 이미 일정 시각이 지난 일정은 알리지 않고 표시만 한다.
     * 화면에서 주기적으로(예: 30초마다) 호출한다.
     *
     * @return 지금 알려야 하는 일정 목록 (없으면 빈 목록)
     */
    public synchronized List<Schedule> collectDueAlerts(LocalDateTime now) {
        List<Schedule> due = new ArrayList<>();
        boolean changed = false;
        for (Schedule s : scheduleList) {
            LocalDateTime alertAt = s.getAlertDateTime();
            if (s.isCompleted() || s.isAlerted() || alertAt == null || now.isBefore(alertAt)) {
                continue;
            }
            s.setAlerted(true);
            changed = true;
            if (now.isBefore(s.getEventDateTime())) {
                due.add(s);
            }
        }
        if (changed) {
            saveInBackground();
        }
        return due;
    }

    // ===== 태그 =====

    /** @return 등록된 태그 이름들 (만든 순서) */
    public synchronized List<String> getTagNames() {
        return new ArrayList<>(tagFields.keySet());
    }

    /** @return 태그의 입력 항목들 (첫 항목이 일정 제목). 등록되지 않은 태그면 기본 항목(제목, 시간) */
    public synchronized List<String> getTagFields(String tag) {
        List<String> fields = tagFields.get(tag);
        return fields == null ? new ArrayList<>(Arrays.asList("제목", "시간")) : new ArrayList<>(fields);
    }

    /**
     * 태그 이름·입력 항목이 올바른지 검사한다.
     *
     * @param isNew true 면 새 태그(이미 있는 이름은 안 됨), false 면 기존 태그 수정(있는 이름이어야 함)
     * @return 문제가 없으면 null, 있으면 사용자에게 보여 줄 안내 문구
     */
    public synchronized String validateTag(String name, List<String> fields, boolean isNew) {
        String tagName = name == null ? "" : name.trim();
        if (!isValidName(tagName)) {
            return "태그 이름은 1~" + MAX_NAME_LENGTH + "자로 적어 주세요.\n(쉼표 , 세미콜론 ; 세로줄 | 콜론 : 등호 = 큰따옴표 \" 는 쓸 수 없어요)";
        }
        if (isNew && findTag(tagName) != null) {
            return "이미 있는 태그 이름이에요.";
        }
        if (!isNew && findTag(tagName) == null) {
            return "없는 태그예요.";
        }
        if (cleanFields(fields) == null) {
            return "입력 항목을 1개 이상 적어 주세요.\n(항목 이름에도 쉼표 , 세미콜론 ; 세로줄 | 콜론 : 등호 = 큰따옴표 \" 는 쓸 수 없어요)";
        }
        return null;
    }

    /** 새 태그를 만든다. @return 만들었으면 true */
    public synchronized boolean addTag(String name, List<String> fields) {
        if (validateTag(name, fields, true) != null) {
            return false;
        }
        tagFields.put(name.trim(), cleanFields(fields));
        saveTagsInBackground();
        return true;
    }

    /** 기존 태그의 입력 항목을 바꾼다. (이미 등록된 일정의 내용은 그대로 둔다) @return 바꿨으면 true */
    public synchronized boolean updateTag(String name, List<String> fields) {
        if (validateTag(name, fields, false) != null) {
            return false;
        }
        tagFields.put(findTag(name.trim()), cleanFields(fields));
        saveTagsInBackground();
        return true;
    }

    /** 태그를 지운다. 태그는 최소 1개가 남아 있어야 한다. 이미 등록된 일정의 태그 표시는 그대로 남는다. @return 지웠으면 true */
    public synchronized boolean removeTag(String name) {
        String key = findTag(name == null ? "" : name.trim());
        if (key == null || tagFields.size() <= 1) {
            return false;
        }
        tagFields.remove(key);
        saveTagsInBackground();
        return true;
    }

    /** 태그 이름·항목 이름으로 쓸 수 있는지 (1~20자, 저장 형식에 쓰이는 문자 제외) */
    public static boolean isValidName(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty() || t.length() > MAX_NAME_LENGTH) {
            return false;
        }
        for (char c : t.toCharArray()) {
            if (INVALID_NAME_CHARS.indexOf(c) >= 0) {
                return false;
            }
        }
        return true;
    }

    /** 이름이 같은(대소문자 무시) 등록 태그의 실제 이름. 없으면 null */
    private String findTag(String name) {
        for (String key : tagFields.keySet()) {
            if (key.equalsIgnoreCase(name)) {
                return key;
            }
        }
        return null;
    }

    /** 공백·중복 항목을 정리한다. 쓸 수 없는 이름이 있거나 하나도 없으면 null */
    private static List<String> cleanFields(List<String> fields) {
        List<String> cleaned = new ArrayList<>();
        if (fields != null) {
            for (String field : fields) {
                String t = field == null ? "" : field.trim();
                if (t.isEmpty()) {
                    continue;
                }
                if (!isValidName(t)) {
                    return null;
                }
                boolean duplicated = false;
                for (String c : cleaned) {
                    duplicated |= c.equalsIgnoreCase(t);
                }
                if (!duplicated) {
                    cleaned.add(t);
                }
            }
        }
        return cleaned.isEmpty() ? null : cleaned;
    }

    // ===== 저장 =====

    /** 파일 저장에 실패했을 때 알림을 받을 리스너 등록 (백그라운드 스레드에서 호출됨) */
    public void setSaveFailureListener(Consumer<String> listener) {
        this.saveFailureListener = listener;
    }

    /** 저장 대기 중인 작업이 끝날 때까지 기다린 뒤 백그라운드 저장 스레드를 종료한다. */
    public void shutdown() {
        saveExecutor.shutdown();
        try {
            saveExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ===== 내부 =====

    /** 현재 일정 목록의 복사본을 만들어 백그라운드 스레드에서 CSV 로 저장한다. */
    private void saveInBackground() {
        final List<Schedule> snapshot = new ArrayList<>(scheduleList.size());
        for (Schedule s : scheduleList) {
            snapshot.add(new Schedule(s.getScheduleID(), s.getTargetDate(), s.getTag(), s.getContent(),
                    s.isExcluded(), s.isCompleted(), s.getAlertHours(), s.isAlerted()));
        }
        runInBackground(() -> fileManager.saveToCSV(snapshot),
                "일정 파일(schedule_data.csv)을 저장하지 못했습니다.\n"
                        + "다른 프로그램(예: 엑셀)에서 파일을 열어 두었는지 확인해 주세요.");
    }

    /** 현재 태그 목록의 복사본을 만들어 백그라운드 스레드에서 저장한다. */
    private void saveTagsInBackground() {
        final Map<String, List<String>> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : tagFields.entrySet()) {
            snapshot.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        runInBackground(() -> fileManager.saveTags(snapshot),
                "태그 파일(schedule_tags.txt)을 저장하지 못했습니다.\n"
                        + "다른 프로그램에서 파일을 열어 두었는지 확인해 주세요.");
    }

    /** 저장 작업을 저장 스레드에 맡긴다. 실패하면 등록된 리스너로 알린다. */
    private void runInBackground(BooleanSupplier job, String failureMessage) {
        Runnable task = () -> {
            boolean saved = job.getAsBoolean();
            Consumer<String> listener = saveFailureListener;
            if (!saved && listener != null) {
                listener.accept(failureMessage);
            }
        };
        try {
            saveExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            task.run(); // 이미 종료 처리 중이면 그 자리에서 바로 저장
        }
    }
}