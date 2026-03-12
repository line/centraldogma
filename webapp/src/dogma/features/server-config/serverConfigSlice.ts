import { createSlice, PayloadAction } from '@reduxjs/toolkit';

export interface ServerConfigState {
  mtlsEnabled: boolean;
  isLoaded: boolean;
}

const initialState: ServerConfigState = {
  mtlsEnabled: false,
  isLoaded: false,
};

const serverConfigSlice = createSlice({
  name: 'serverConfig',
  initialState,
  reducers: {
    setServerConfig: (state: ServerConfigState, action: PayloadAction<{ mtlsEnabled: boolean }>) => {
      state.mtlsEnabled = action.payload.mtlsEnabled;
      state.isLoaded = true;
    },
  },
});

export const { setServerConfig } = serverConfigSlice.actions;
export const serverConfigReducer = serverConfigSlice.reducer;
