/*
 * Copyright (c) 2018, Daniel Gultsch All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package eu.siacs.conversations.ui;

import android.animation.Animator;
import android.animation.AnimatorInflater;
import android.app.Activity;
import android.app.Fragment;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.LinearLayoutManager;

import java.util.ArrayList;
import java.util.List;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.FragmentConversationsOverviewBinding;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.ui.adapter.ConversationAdapter;
import eu.siacs.conversations.ui.interfaces.OnConversationSelected;
import eu.siacs.conversations.ui.util.PendingActionHelper;
import eu.siacs.conversations.ui.util.PendingItem;
import eu.siacs.conversations.ui.util.ScrollState;
import eu.siacs.conversations.utils.MenuDoubleTabUtil;

public class ConversationsOverviewFragment extends XmppFragment {

    private static final String STATE_SCROLL_POSITION = ConversationsOverviewFragment.class.getName() + ".scroll_state";

    private final List<Conversation> conversations = new ArrayList<>();
    private final PendingItem<Conversation> swipedConversation = new PendingItem<>();
    private final PendingItem<ScrollState> pendingScrollState = new PendingItem<>();
    private FragmentConversationsOverviewBinding binding;
    private ConversationAdapter conversationsAdapter;
    private XmppActivity activity;
    private PendingActionHelper pendingActionHelper = new PendingActionHelper();

    public static Conversation getSuggestion(Activity activity) {
        final Conversation exception;
        Fragment fragment = activity.getFragmentManager().findFragmentById(R.id.main_fragment);
        if (fragment instanceof ConversationsOverviewFragment) {
            exception = ((ConversationsOverviewFragment) fragment).swipedConversation.peek();
        } else {
            exception = null;
        }
        return getSuggestion(activity, exception);
    }

    public static Conversation getSuggestion(Activity activity, Conversation exception) {
        Fragment fragment = activity.getFragmentManager().findFragmentById(R.id.main_fragment);
        if (fragment instanceof ConversationsOverviewFragment) {
            List<Conversation> conversations = ((ConversationsOverviewFragment) fragment).conversations;
            if (conversations.size() > 0) {
                Conversation suggestion = conversations.get(0);
                if (suggestion == exception) {
                    if (conversations.size() > 1) {
                        return conversations.get(1);
                    }
                } else {
                    return suggestion;
                }
            }
        }
        return null;

    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        if (savedInstanceState == null) {
            return;
        }
        pendingScrollState.push(savedInstanceState.getParcelable(STATE_SCROLL_POSITION));
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        if (activity instanceof XmppActivity) {
            this.activity = (XmppActivity) activity;
        } else {
            throw new IllegalStateException("Trying to attach fragment to activity that is not an XmppActivity");
        }
    }

	@Override
	public void onDestroyView() {
		Log.d(Config.LOGTAG,"ConversationsOverviewFragment.onDestroyView()");
		super.onDestroyView();
		this.binding = null;
		this.conversationsAdapter = null;
	}

	@Override
	public void onDestroy() {
		Log.d(Config.LOGTAG,"ConversationsOverviewFragment.onDestroy()");
		super.onDestroy();

	}

    @Override
    public void onPause() {
        Log.d(Config.LOGTAG, "ConversationsOverviewFragment.onPause()");
        pendingActionHelper.execute();
        super.onPause();
    }

    @Override
    public void onDetach() {
        super.onDetach();
        this.activity = null;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        this.binding = DataBindingUtil.inflate(inflater, R.layout.fragment_conversations_overview, container, false);
        this.binding.fab.setOnClickListener((view) -> StartConversationActivity.launch(getActivity()));
        this.conversationsAdapter = new ConversationAdapter(this.activity, this.conversations);
        if (this.conversations.size() > 0) {
            this.activity.xmppConnectionService.updateNotificationChannels();
        }
        this.conversationsAdapter.setConversationClickListener((view, conversation) -> {
            if (activity instanceof OnConversationSelected) {
                ((OnConversationSelected) activity).onConversationSelected(conversation);
            } else {
                Log.w(ConversationsOverviewFragment.class.getCanonicalName(), "Activity does not implement OnConversationSelected");
            }
        });

        this.binding.list.setAdapter(this.conversationsAdapter);
        this.binding.list.setLayoutManager(new LinearLayoutManager(getActivity(), LinearLayoutManager.VERTICAL, false));
        return binding.getRoot();
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater menuInflater) {
        menuInflater.inflate(R.menu.fragment_conversations_overview, menu);
    }

    @Override
    public void onBackendConnected() {
        refresh();
    }

    @Override
    public void onSaveInstanceState(Bundle bundle) {
        super.onSaveInstanceState(bundle);
        ScrollState scrollState = getScrollState();
        if (scrollState != null) {
            bundle.putParcelable(STATE_SCROLL_POSITION, scrollState);
        }
    }

    private ScrollState getScrollState() {
        if (this.binding == null) {
            return null;
        }
        LinearLayoutManager layoutManager = (LinearLayoutManager) this.binding.list.getLayoutManager();
        int position = layoutManager.findFirstVisibleItemPosition();
        final View view = this.binding.list.getChildAt(0);
        if (view != null) {
            return new ScrollState(position, view.getTop());
        } else {
            return new ScrollState(position, 0);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        Log.d(Config.LOGTAG, "ConversationsOverviewFragment.onStart()");
        if (activity.xmppConnectionService != null) {
            refresh();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.d(Config.LOGTAG, "ConversationsOverviewFragment.onResume()");
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (MenuDoubleTabUtil.shouldIgnoreTap()) {
            return false;
        }
        switch (item.getItemId()) {
            case R.id.action_search:
                startActivity(new Intent(getActivity(), SearchActivity.class));
                activity.overridePendingTransition(R.animator.fade_in, R.animator.fade_out);
                return true;
        }
        return super.onOptionsItemSelected(item);
	}

    @Override
    public Animator onCreateAnimator(int transit, boolean enter, int nextAnim) {
        int animator = enter ? R.animator.fade_left_in : R.animator.fade_left_out;
        return AnimatorInflater.loadAnimator(getActivity(), animator);
    }

    @Override
    void refresh() {
        if (this.binding == null || this.activity == null) {
            Log.d(Config.LOGTAG, "ConversationsOverviewFragment.refresh() skipped updated because view binding or activity was null");
            return;
        }
        this.activity.xmppConnectionService.populateWithOrderedConversations(this.conversations);
        if (this.conversations.size() > 0) {
            this.activity.xmppConnectionService.updateNotificationChannels();
        }
        this.conversationsAdapter.notifyDataSetChanged();
        ScrollState scrollState = pendingScrollState.pop();
        if (scrollState != null) {
            setScrollPosition(scrollState);
        }
    }

    private void setScrollPosition(ScrollState scrollPosition) {
        if (scrollPosition != null) {
            LinearLayoutManager layoutManager = (LinearLayoutManager) binding.list.getLayoutManager();
            layoutManager.scrollToPositionWithOffset(scrollPosition.position, scrollPosition.offset);
        }
    }
}