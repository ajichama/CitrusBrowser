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

package io.github.ajichama.citrusbrowser.legacy.action.view

import androidx.core.content.IntentCompat

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.Menu
import android.widget.Toast
import androidx.activity.viewModels
import io.github.ajichama.citrusbrowser.legacy.R
import io.github.ajichama.citrusbrowser.legacy.action.Action
import io.github.ajichama.citrusbrowser.legacy.action.ActionList
import io.github.ajichama.citrusbrowser.legacy.action.ActionNameArray
import io.github.ajichama.citrusbrowser.legacy.databinding.ScrollEditTextModel
import io.github.ajichama.citrusbrowser.legacy.databinding.ScrollEdittextBinding
import io.github.ajichama.citrusbrowser.legacy.utils.util.JsonConvertable
import io.github.ajichama.citrusbrowser.ui.app.ThemeActivity

class ActionStringActivity : ThemeActivity() {
    private var mTarget: Int = 0
    private var mActionNameArray: ActionNameArray? = null

    private lateinit var binding: ScrollEdittextBinding

    private val viewModel by viewModels<ScrollEditTextModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ScrollEdittextBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.lifecycleOwner = this
        binding.model = viewModel

        val intent = intent ?: throw NullPointerException("Intent is null")

        mActionNameArray = IntentCompat.getParcelableExtra(intent, ActionNameArray.INTENT_EXTRA, ActionNameArray::class.java)

        val action = IntentCompat.getParcelableExtra(intent, EXTRA_ACTION, Parcelable::class.java)
        if (action != null) {
            if (action is Action) {
                mTarget = ACTION_ACTIVITY
                viewModel.text.value = (action as JsonConvertable).toJsonString()
                return
            } else if (action is ActionList) {
                mTarget = ACTION_LIST_ACTIVITY
                viewModel.text.value = (action as JsonConvertable).toJsonString()
                return
            }
            throw IllegalArgumentException("ARG_ACTION is not action or actionlist")
        } else {
            mTarget = getIntent().getIntExtra(EXTRA_ACTIVITY, ACTION_ACTIVITY)

            when (mTarget) {
                ACTION_ACTIVITY -> ActionActivity.Builder(this)
                        .show(ACTION_ACTIVITY)
                ACTION_LIST_ACTIVITY -> ActionListActivity.Builder(this)
                        .show(ACTION_LIST_ACTIVITY)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(R.string.json_to_action).setOnMenuItemClickListener {
            val jsonStr = viewModel.text.value ?: ""

            if (callingPackage == null) {
                when (mTarget) {
                    ACTION_ACTIVITY -> ActionActivity.Builder(this@ActionStringActivity)
                            .setDefaultAction(Action(jsonStr))
                            .setActionNameArray(mActionNameArray)
                            .show(ACTION_ACTIVITY)
                    ACTION_LIST_ACTIVITY -> ActionListActivity.Builder(this@ActionStringActivity)
                            .setDefaultActionList(ActionList(jsonStr))
                            .setActionNameArray(mActionNameArray)
                            .show(ACTION_LIST_ACTIVITY)
                }
            } else {
                var result = false
                var actionObj: Parcelable? = null
                when (mTarget) {
                    ACTION_ACTIVITY -> {
                        val action = Action()
                        result = action.fromJsonString(jsonStr)
                        actionObj = action
                    }
                    ACTION_LIST_ACTIVITY -> {
                        val actionList = ActionList()
                        result = actionList.fromJsonString(jsonStr)
                        actionObj = actionList
                    }
                }

                if (result) {
                    val intent = Intent()
                    intent.putExtra(EXTRA_ACTION, actionObj)
                    setResult(Activity.RESULT_OK, intent)
                    finish()
                } else {
                    Toast.makeText(this, R.string.invalid_json_format, Toast.LENGTH_SHORT).show()
                }

            }

            false
        }
        return super.onCreateOptionsMenu(menu)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            ACTION_ACTIVITY -> {
                if (resultCode != Activity.RESULT_OK || data == null) return

                val action = IntentCompat.getParcelableExtra(data, ActionActivity.EXTRA_ACTION, Action::class.java) ?: return
                viewModel.text.value = action.toJsonString()
            }
            ACTION_LIST_ACTIVITY -> {
                if (resultCode != Activity.RESULT_OK || data == null) return

                val action = IntentCompat.getParcelableExtra(data, ActionListActivity.EXTRA_ACTION_LIST, ActionList::class.java)
                    ?: return
                viewModel.text.value = action.toJsonString()
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    companion object {
        const val EXTRA_ACTIVITY = "MakeActionStringActivity.extra.activity"
        const val EXTRA_ACTION = "MakeActionStringActivity.extra.action"
        const val ACTION_ACTIVITY = 1
        const val ACTION_LIST_ACTIVITY = 2
    }
}
