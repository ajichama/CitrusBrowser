package io.github.ajichama.citrusbrowser.legacy.utils.appinfo;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ListView;

import java.util.ArrayList;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.os.BundleCompat;
import androidx.fragment.app.ListFragment;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;

public class ApplicationListFragment extends ListFragment implements LoaderManager.LoaderCallbacks<ArrayList<AppInfo>> {
    private static final String TYPE = "type";
    private static final String INTENT = "Intent";

    private AppInfoListAdapter adapter;
    private OnAppSelectListener mListener;

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        LoaderManager.getInstance(this).initLoader(0, null, this);
    }

    @Override
    public void onListItemClick(@NonNull ListView l, @NonNull View v, int position, long id) {
        if (mListener != null && getArguments() != null) {
            AppInfo appInfo = adapter.getItem(position);
            mListener.onAppSelected(getArguments().getInt(TYPE, -1), appInfo);
        }
    }

    @NonNull
    @Override
    public Loader<ArrayList<AppInfo>> onCreateLoader(int id, @Nullable Bundle args) {
        Intent target = getArguments() != null
                ? BundleCompat.getParcelable(getArguments(), INTENT, Intent.class)
                : null;

        return new AppListTask(requireContext(), target, false);
    }

    @Override
    public void onLoadFinished(@NonNull Loader<ArrayList<AppInfo>> loader, ArrayList<AppInfo> data) {
        adapter = new AppInfoListAdapter(requireContext(), data);
        setListAdapter(adapter);
    }

    @Override
    public void onLoaderReset(@NonNull Loader<ArrayList<AppInfo>> loader) {

    }

    public static ApplicationListFragment newInstance(@NonNull Intent intent) {
        ApplicationListFragment fragment = new ApplicationListFragment();
        Bundle bundle = new Bundle();
        bundle.putParcelable(INTENT, intent);
        fragment.setArguments(bundle);
        return fragment;
    }

    public static ApplicationListFragment newInstance(int type, @NonNull Intent intent) {
        ApplicationListFragment fragment = new ApplicationListFragment();
        Bundle bundle = new Bundle();
        bundle.putInt(TYPE, type);
        bundle.putParcelable(INTENT, intent);
        fragment.setArguments(bundle);
        return fragment;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        try {
            mListener = (OnAppSelectListener) getActivity();
        } catch (ClassCastException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mListener = null;
    }

    public interface OnAppSelectListener {
        void onAppSelected(int type, AppInfo info);
    }
}
