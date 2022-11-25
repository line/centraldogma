import { createSlice, PayloadAction } from '@reduxjs/toolkit';

export interface MessageState {
  title: string;
  text: string;
  type: 'error' | 'info' | 'warning' | 'success' | 'loading';
}

const initialState: MessageState = {
  title: '',
  text: '',
  type: 'info',
};

export const messageSlice = createSlice({
  name: 'message',
  initialState,
  reducers: {
    createMessage(state: MessageState, action: PayloadAction<MessageState>) {
      state.title = action.payload.title;
      state.text = action.payload.text;
      state.type = action.payload.type;
    },
  },
});

export const { createMessage } = messageSlice.actions;
export const messageReducer = messageSlice.reducer;
