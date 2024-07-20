import { createSlice, PayloadAction } from '@reduxjs/toolkit';

export type ProjectFilterType = 'ALL' | 'MEMBER' | 'CREATOR';

export type FilterState = {
  projectFilter: ProjectFilterType;
  isInitialProjectFilter: boolean;
};

const initialState: FilterState = {
  projectFilter: 'MEMBER',
  isInitialProjectFilter: true,
};

const filterSlice = createSlice({
  name: 'filterState',
  initialState,
  reducers: {
    setProjectFilter(state: FilterState, action: PayloadAction<ProjectFilterType>) {
      state.projectFilter = action.payload;
      state.isInitialProjectFilter = false;
    },
  },
});

export const { setProjectFilter } = filterSlice.actions;

export const filterReducer = filterSlice.reducer;
