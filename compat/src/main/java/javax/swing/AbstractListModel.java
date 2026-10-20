package javax.swing;

import java.io.Serializable;
import java.util.EventListener;
import javax.swing.event.EventListenerList;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;

/** Android shim for javax.swing.AbstractListModel (same contract as the Swing class). */
public abstract class AbstractListModel<E> implements ListModel<E>, Serializable {
  protected EventListenerList listenerList = new EventListenerList();

  @Override
  public void addListDataListener(ListDataListener l) {
    listenerList.add(ListDataListener.class, l);
  }

  @Override
  public void removeListDataListener(ListDataListener l) {
    listenerList.remove(ListDataListener.class, l);
  }

  public ListDataListener[] getListDataListeners() {
    return listenerList.getListeners(ListDataListener.class);
  }

  protected void fireContentsChanged(Object source, int index0, int index1) {
    Object[] listeners = listenerList.getListenerList();
    ListDataEvent e = null;
    for (int i = listeners.length - 2; i >= 0; i -= 2) {
      if (listeners[i] == ListDataListener.class) {
        if (e == null) e = new ListDataEvent(source, ListDataEvent.CONTENTS_CHANGED, index0, index1);
        ((ListDataListener) listeners[i + 1]).contentsChanged(e);
      }
    }
  }

  protected void fireIntervalAdded(Object source, int index0, int index1) {
    Object[] listeners = listenerList.getListenerList();
    ListDataEvent e = null;
    for (int i = listeners.length - 2; i >= 0; i -= 2) {
      if (listeners[i] == ListDataListener.class) {
        if (e == null) e = new ListDataEvent(source, ListDataEvent.INTERVAL_ADDED, index0, index1);
        ((ListDataListener) listeners[i + 1]).intervalAdded(e);
      }
    }
  }

  protected void fireIntervalRemoved(Object source, int index0, int index1) {
    Object[] listeners = listenerList.getListenerList();
    ListDataEvent e = null;
    for (int i = listeners.length - 2; i >= 0; i -= 2) {
      if (listeners[i] == ListDataListener.class) {
        if (e == null) e = new ListDataEvent(source, ListDataEvent.INTERVAL_REMOVED, index0, index1);
        ((ListDataListener) listeners[i + 1]).intervalRemoved(e);
      }
    }
  }

  public <T extends EventListener> T[] getListeners(Class<T> listenerType) {
    return listenerList.getListeners(listenerType);
  }
}
