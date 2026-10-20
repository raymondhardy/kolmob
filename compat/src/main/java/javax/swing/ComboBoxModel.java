package javax.swing;

/** Android shim for javax.swing.ComboBoxModel. */
public interface ComboBoxModel<E> extends ListModel<E> {
  void setSelectedItem(Object anItem);

  Object getSelectedItem();
}
