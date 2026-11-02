import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Arrays;
import java.util.List;

/**
 * 태그를 만들고 고치고 지우는 대화상자 (일정 추가·변경 창의 [태그 관리] 버튼)
 *
 * <p>태그는 "이름 + 입력 항목 목록" 이다. 첫 항목은 일정 제목이 되고,
 * '시간' 항목이 있어야 그 태그의 일정에 알림을 쓸 수 있다.</p>
 *
 * @author 정현우
 * @version 1.0
 */
public class TagDialog extends JDialog {
    private final ScheduleManager manager;
    private final DefaultListModel<String> listModel = new DefaultListModel<>();
    private final JList<String> tagList = new JList<>(listModel);

    public TagDialog(Window owner, ScheduleManager manager) {
        super(owner, "Plan:Null - 태그 관리", Dialog.ModalityType.APPLICATION_MODAL);
        this.manager = manager;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JLabel guide = new JLabel("<html>태그마다 입력 항목을 정해 둘 수 있어요.<br>"
                + "첫 항목은 일정 제목이 되고, '시간' 항목이 있어야 알림을 쓸 수 있어요.</html>");
        guide.setBorder(new EmptyBorder(12, 14, 8, 14));

        tagList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tagList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                String name = String.valueOf(value);
                String fields = String.join(", ", TagDialog.this.manager.getTagFields(name));
                return super.getListCellRendererComponent(list, name + "    (" + fields + ")", index, isSelected, cellHasFocus);
            }
        });
        tagList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && tagList.getSelectedValue() != null) {
                    editTag(tagList.getSelectedValue());
                }
            }
        });
        JScrollPane scroll = new JScrollPane(tagList);
        scroll.setPreferredSize(new Dimension(420, 200));
        JPanel center = new JPanel(new BorderLayout());
        center.setBorder(new EmptyBorder(0, 14, 0, 14));
        center.add(scroll, BorderLayout.CENTER);

        JButton addBtn = new JButton("추가");
        JButton editBtn = new JButton("수정");
        JButton delBtn = new JButton("삭제");
        JButton closeBtn = new JButton("닫기");
        addBtn.addActionListener(e -> editTag(null));
        editBtn.addActionListener(e -> {
            String selected = tagList.getSelectedValue();
            if (selected == null) {
                info("수정할 태그를 먼저 선택하세요.");
            } else {
                editTag(selected);
            }
        });
        delBtn.addActionListener(e -> deleteTag());
        closeBtn.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 10));
        buttons.add(addBtn);
        buttons.add(editBtn);
        buttons.add(delBtn);
        buttons.add(closeBtn);

        add(guide, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        reload(null);
        pack();
        setLocationRelativeTo(owner);
    }

    public void showDialog() {
        setVisible(true);
    }

    /** 새 태그를 만들거나(existing == null) 기존 태그의 입력 항목을 고친다. */
    private void editTag(String existing) {
        JTextField nameField = new JTextField(existing == null ? "" : existing, 18);
        nameField.setEnabled(existing == null);              // 이름은 만들 때만 정한다
        JTextField fieldsField = new JTextField(
                existing == null ? "제목, 장소, 시간" : String.join(", ", manager.getTagFields(existing)), 28);
        JPanel panel = new JPanel(new GridLayout(0, 1, 0, 4));
        panel.add(new JLabel("태그 이름"));
        panel.add(nameField);
        panel.add(new JLabel("입력 항목 (쉼표로 구분 · 첫 항목이 일정 제목이 돼요)"));
        panel.add(fieldsField);
        String title = existing == null ? "새 태그 만들기" : "태그 수정";
        while (true) {
            int answer = JOptionPane.showConfirmDialog(this, panel, title,
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) {
                return;
            }
            String name = nameField.getText().trim();
            List<String> fields = Arrays.asList(fieldsField.getText().split("[,，]"));
            String problem = manager.validateTag(name, fields, existing == null);
            if (problem == null) {
                if (existing == null) {
                    manager.addTag(name, fields);
                } else {
                    manager.updateTag(existing, fields);
                }
                reload(name);
                return;
            }
            JOptionPane.showMessageDialog(this, problem, "입력 확인", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void deleteTag() {
        String name = tagList.getSelectedValue();
        if (name == null) {
            info("삭제할 태그를 먼저 선택하세요.");
            return;
        }
        if (manager.getTagNames().size() <= 1) {
            info("태그는 최소 1개가 있어야 해요.");
            return;
        }
        int answer = JOptionPane.showOptionDialog(this,
                "'" + name + "' 태그를 삭제할까요?\n이미 등록된 일정은 그대로 남아요.", "태그 삭제",
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null,
                new Object[]{"삭제", "취소"}, "취소");
        if (answer == 0 && manager.removeTag(name)) {
            reload(null);
        }
    }

    private void reload(String select) {
        listModel.clear();
        for (String name : manager.getTagNames()) {
            listModel.addElement(name);
        }
        if (select != null) {
            tagList.setSelectedValue(select, true);
        }
    }

    private void info(String message) {
        JOptionPane.showMessageDialog(this, message, "태그 관리", JOptionPane.INFORMATION_MESSAGE);
    }
}