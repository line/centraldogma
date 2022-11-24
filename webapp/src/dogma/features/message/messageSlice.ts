import { createSlice, PayloadAction } from '@reduxjs/toolkit';

export interface MessageState {
  errorText: string;
  errorType: 'error' | 'info' | 'warning' | 'success' | 'loading';
}

const initialState: MessageState = {
  errorText: '',
  errorType: 'info',
};

export const messageSlice = createSlice({
  name: 'message',
  initialState,
  reducers: {
    createMessageError(state: MessageState, action: PayloadAction<string>) {
      state.errorText = action.payload;
      state.errorType = 'error';
    },
    createMessageWarning(state: MessageState, action: PayloadAction<string>) {
      state.errorText = action.payload;
      state.errorType = 'warning';
    },
    createMessageInfo(state: MessageState, action: PayloadAction<string>) {
      state.errorText = action.payload;
      state.errorType = 'info';
    },
  },
});

export const { createMessageError, createMessageWarning, createMessageInfo } = messageSlice.actions;
export const messageReducer = messageSlice.reducer;
