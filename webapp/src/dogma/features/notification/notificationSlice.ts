import { createSlice, PayloadAction } from '@reduxjs/toolkit';
import { ReactNode } from 'react';
import { StyleProps } from '@chakra-ui/system';

type NotificationType = 'error' | 'info' | 'warning' | 'success' | 'loading';

export interface Notification {
  title: string;
  description: string | ReactNode;
  type: NotificationType;
  containerStyle?: StyleProps;
  timestamp: number;
}

const initialState: Notification = {
  title: '',
  description: '',
  type: 'info',
  containerStyle: {},
  timestamp: null,
};

export const notificationSlice = createSlice({
  name: 'message',
  initialState,
  reducers: {
    createNotification(state: Notification, action: PayloadAction<Notification>) {
      state.title = action.payload.title;
      state.description = action.payload.description;
      state.type = action.payload.type;
      state.containerStyle = action.payload.containerStyle;
      state.timestamp = action.payload.timestamp;
    },
    resetState(state: Notification) {
      Object.assign(state, initialState);
    },
  },
});

export const { createNotification, resetState } = notificationSlice.actions;
export const notificationReducer = notificationSlice.reducer;

export function newNotification(
  title: string,
  description: string | ReactNode,
  type: NotificationType,
  containerStyle?: StyleProps,
): PayloadAction<Notification, 'message/createNotification'> {
  return createNotification({ title, description, type, containerStyle, timestamp: Date.now() });
}
