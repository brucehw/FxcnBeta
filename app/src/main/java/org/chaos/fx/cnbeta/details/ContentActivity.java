/*
 * Copyright 2016 Chaos
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.chaos.fx.cnbeta.details;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager;
import android.support.v7.app.ActionBar;
import android.support.v7.widget.Toolbar;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;

import org.chaos.fx.cnbeta.R;
import org.chaos.fx.cnbeta.net.CnBetaApiHelper;
import org.chaos.fx.cnbeta.net.WebApi;
import org.chaos.fx.cnbeta.net.exception.RequestFailedException;
import org.chaos.fx.cnbeta.net.model.HasReadArticle;
import org.chaos.fx.cnbeta.net.model.WebCommentResult;

import java.io.IOException;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.exceptions.Exceptions;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;
import io.realm.Realm;
import me.imid.swipebacklayout.lib.app.SwipeBackActivity;
import okhttp3.ResponseBody;

/**
 * @author Chaos
 *         2015/11/15.
 */
public class ContentActivity extends SwipeBackActivity implements
        ContentFragment.OnShowCommentListener, CommentFragment.OnCommentUpdateListener {

    private static final String TAG = "ContentActivity";

    private static final String KEY_SID = "sid";
    private static final String KEY_TOPIC_LOGO = "topic_logo";

    public static void start(Context context, int sid, String topicLogoLink) {
        Intent intent = new Intent(context, ContentActivity.class);
        intent.putExtra(KEY_SID, sid);
        intent.putExtra(KEY_TOPIC_LOGO, topicLogoLink);
        context.startActivity(intent);
    }

    private int mSid;
    private String mLogoLink;

    private String mHtmlBody;
    private WebCommentResult mWebCommentResult;

    @BindView(R.id.pager) ViewPager mViewPager;

    @BindView(R.id.error_layout) View mErrorLayout;
    @BindView(R.id.loading_view) ProgressBar mLoadingView;

    private SectionsPagerAdapter mPagerAdapter;

    private Disposable mArticleDisposable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_content);

        ButterKnife.bind(this);

        mSid = getIntent().getIntExtra(KEY_SID, -1);
        mLogoLink = getIntent().getStringExtra(KEY_TOPIC_LOGO);

        if (mSid != -1) {
            Realm realm = Realm.getDefaultInstance();
            realm.beginTransaction();
            HasReadArticle readArticle = new HasReadArticle();
            readArticle.setSid(mSid);
            realm.copyToRealmOrUpdate(readArticle);
            realm.commitTransaction();
        }

        setupActionBar();

        requestArticleHtml();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mArticleDisposable.dispose();
    }

    private void setupActionBar() {
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            setTitle(R.string.content);
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    private void setupViewPager() {
        mPagerAdapter = new SectionsPagerAdapter(getSupportFragmentManager());
        mViewPager.setAdapter(mPagerAdapter);
        mViewPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                setSwipeBackEnable(position == 0);
                setTitle(mPagerAdapter.getPageTitle(position));
            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                scrollToFinishActivity();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onShowComment() {
        mViewPager.setCurrentItem(1, true);
    }

    @Override
    public void onCommentUpdated(int count) {
        mPagerAdapter.contentFragment.updateCommentCount(count);
    }

    @OnClick(R.id.error_button)
    public void requestArticleHtml() {
        mLoadingView.setVisibility(View.VISIBLE);
        mErrorLayout.setVisibility(View.GONE);

        mArticleDisposable = CnBetaApiHelper.getArticleHtml(mSid)
                .subscribeOn(Schedulers.io())
                .map(new Function<ResponseBody, String>() {
                    @Override
                    public String apply(ResponseBody responseBody) {
                        try {
                            mHtmlBody = responseBody.string();
                            return CnBetaApiHelper.getSNFromArticleBody(mHtmlBody);
                        } catch (IOException e) {
                            throw Exceptions.propagate(e);
                        }
                    }
                })
                .flatMap(new Function<String, Observable<WebApi.Result<WebCommentResult>>>() {
                    @Override
                    public Observable<WebApi.Result<WebCommentResult>> apply(String sn) {
                        return CnBetaApiHelper.getCommentJson(mSid, sn);
                    }
                })
                .map(new Function<WebApi.Result<WebCommentResult>, WebCommentResult>() {
                    @Override
                    public WebCommentResult apply(WebApi.Result<WebCommentResult> result) {
                        if (result.isSuccess()) {
                            return result.result;
                        } else {
                            throw new RequestFailedException();
                        }
                    }
                })
                .observeOn(AndroidSchedulers.mainThread())
                .retry(3)
                .subscribe(new Consumer<WebCommentResult>() {
                    @Override
                    public void accept(WebCommentResult result) throws Exception {
                        mWebCommentResult = result;

                        mLoadingView.setVisibility(View.GONE);
                        setupViewPager();
                    }
                }, new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable e) throws Exception {
                        mLoadingView.setVisibility(View.GONE);
                        mErrorLayout.setVisibility(View.VISIBLE);
                    }
                });
    }

    String getToken() {
        if (mWebCommentResult == null) {
            return null;
        }
        return mWebCommentResult.getToken();
    }

    private class SectionsPagerAdapter extends FragmentPagerAdapter {

        private final String[] contentTitles = new String[]{getString(R.string.content), getString(R.string.comment)};
        ContentFragment contentFragment;
        CommentFragment commentFragment;

        private SectionsPagerAdapter(FragmentManager fm) {
            super(fm);
            commentFragment = CommentFragment.newInstance(mSid, mWebCommentResult);
            contentFragment = ContentFragment.newInstance(mSid, mLogoLink, mHtmlBody,
                    mWebCommentResult.getCommentCount());
        }

        @Override
        public Fragment getItem(int position) {
            switch (position) {
                case 0:
                    return contentFragment;
                case 1:
                    return commentFragment;
            }
            return new Fragment();
        }

        @Override
        public int getCount() {
            return contentTitles.length;
        }

        @Override
        public CharSequence getPageTitle(int position) {
            return contentTitles[position];
        }
    }
}
