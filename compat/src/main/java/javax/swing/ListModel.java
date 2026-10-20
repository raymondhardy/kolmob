package javax.swing;

import javax.swing.event.ListDataListener;

/** Android shim for javax.swing.ListModel (Swing does not exist on Android). */
public interface ListModel<E> {
  int getSize();

  E getElementAt(int index);

  void addListDataListener(ListDataListener l);

  void removeListDataListener(ListDataListener l);
}
