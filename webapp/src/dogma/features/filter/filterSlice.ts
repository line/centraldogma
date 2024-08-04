import { createSlice, PayloadAction } from '@reduxjs/toolkit';

export type FilterType = 'all' | 'me';

type FilterState = {
  projectFilter: FilterType;
};

const filterSlice = createSlice({
  name: 'filterState',
  initialState: {
    projectFilter: 'all',
  },
  reducers: {
    setProjectFilter(state: FilterState, action: PayloadAction<FilterType>) {
      state.projectFilter = action.payload;
    },
  },
});

export const { setProjectFilter } = filterSlice.actions;

export const filterReducer = filterSlice.reducer;
