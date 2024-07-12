import { createSlice, PayloadAction } from '@reduxjs/toolkit';

type NotificationType = 'error' | 'info' | 'warning' | 'success' | 'loading';

export interface Notification {
  title: string;
  text: string;
  type: NotificationType;
  timestamp: number;
}

const initialState: Notification = {
  title: '',
  text: '',
  type: 'info',
  timestamp: Date.now(),
};

export const notificationSlice = createSlice({
  name: 'message',
  initialState,
  reducers: {
    createNotification(state: Notification, action: PayloadAction<Notification>) {
      state.title = action.payload.title;
      state.text = action.payload.text;
      state.type = action.payload.type;
      state.timestamp = action.payload.timestamp;
    },
    resetState(state: Notification) {
      Object.assign(state, initialState);
    },
  },
});

export const { createNotification, resetState } = notificationSlice.actions;
export const notificationReducer = notificationSlice.reducer;

export function newNotification(title: string, text: string, type: NotificationType) {
  return createNotification({ title, text, type, timestamp: Date.now() });
}
