/*
 * Copyright (C) 2017-2019 Hazuki
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

package io.github.ajichama.citrusbrowser.search.di

import android.app.Application
import android.content.Context
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityRetainedComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.ajichama.citrusbrowser.bookmark.repository.BookmarkManager
import io.github.ajichama.citrusbrowser.history.repository.BrowserHistoryManager
import io.github.ajichama.citrusbrowser.search.domain.ISearchUrlRepository
import io.github.ajichama.citrusbrowser.search.domain.ISuggestRepository
import io.github.ajichama.citrusbrowser.search.domain.usecase.SearchSettingsViewUseCase
import io.github.ajichama.citrusbrowser.search.domain.usecase.SearchViewUseCase
import io.github.ajichama.citrusbrowser.search.presentation.settings.SearchSettingsViewModel
import io.github.ajichama.citrusbrowser.search.repository.SearchUrlManager
import io.github.ajichama.citrusbrowser.search.repository.SuggestRepository
import io.github.ajichama.citrusbrowser.ui.provider.ISuggestProvider

@Module
@InstallIn(ActivityRetainedComponent::class)
class SearchSubModule {

    @Provides
    fun provideSuggestRepository(application: Application, suggestProvider: ISuggestProvider): ISuggestRepository {
        return SuggestRepository(application, suggestProvider)
    }

    @Provides
    fun provideSearchUrlRepository(
        @ApplicationContext context: Context,
        moshi: Moshi
    ): ISearchUrlRepository {
        return SearchUrlManager(context, moshi)
    }

    @Provides
    internal fun provideSearchViewUseCase(
        @ApplicationContext context: Context,
        suggestRepository: ISuggestRepository,
        searchUrlRepository: ISearchUrlRepository,
    ): SearchViewUseCase {
        return SearchViewUseCase(
            BookmarkManager.getInstance(context),
            BrowserHistoryManager.getInstance(context),
            suggestRepository,
            searchUrlRepository
        )
    }

    @Provides
    internal fun provideSearchSettingsViewUseCase(
        searchUrlRepository: ISearchUrlRepository
    ): SearchSettingsViewUseCase {
        return SearchSettingsViewUseCase(searchUrlRepository)
    }

    @Provides
    internal fun provideSearchSettingsViewModelFactory(
        application: Application,
        useCase: SearchSettingsViewUseCase,
    ): SearchSettingsViewModel.Factory {
        return SearchSettingsViewModel.Factory(application, useCase)
    }
}
