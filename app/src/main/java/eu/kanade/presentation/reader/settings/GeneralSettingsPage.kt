package eu.kanade.presentation.reader.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import eu.kanade.tachiyomi.util.system.hasDisplayCutout
import mihon.core.superresolution.DenoiseLevel
import mihon.core.superresolution.Quality
import mihon.core.superresolution.SRIndicatorDisplayMode
import mihon.core.superresolution.SRIndicatorPosition
import mihon.core.superresolution.SRModel
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState as preferenceCollectAsState

private val themes = listOf(
    MR.strings.black_background to 1,
    MR.strings.gray_background to 2,
    MR.strings.white_background to 0,
    MR.strings.automatic_background to 3,
)

private val flashColors = listOf(
    MR.strings.pref_flash_style_black to ReaderPreferences.FlashColor.BLACK,
    MR.strings.pref_flash_style_white to ReaderPreferences.FlashColor.WHITE,
    MR.strings.pref_flash_style_white_black to ReaderPreferences.FlashColor.WHITE_BLACK,
)

@Composable
internal fun ColumnScope.GeneralPage(screenModel: ReaderSettingsScreenModel) {
    val readerTheme by screenModel.preferences.readerTheme.preferenceCollectAsState()

    val flashPageState by screenModel.preferences.flashOnPageChange.preferenceCollectAsState()

    val flashMillisPref = screenModel.preferences.flashDurationMillis
    val flashMillis by flashMillisPref.preferenceCollectAsState()

    val flashIntervalPref = screenModel.preferences.flashPageInterval
    val flashInterval by flashIntervalPref.preferenceCollectAsState()

    val flashColorPref = screenModel.preferences.flashColor
    val flashColor by flashColorPref.preferenceCollectAsState()

    SettingsChipRow(MR.strings.pref_reader_theme) {
        themes.map { (labelRes, value) ->
            FilterChip(
                selected = readerTheme == value,
                onClick = { screenModel.preferences.readerTheme.set(value) },
                label = { Text(stringResource(labelRes)) },
            )
        }
    }

    CheckboxItem(
        label = stringResource(MR.strings.pref_show_page_number),
        pref = screenModel.preferences.showPageNumber,
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_fullscreen),
        pref = screenModel.preferences.fullscreen,
    )

    val isFullscreen by screenModel.preferences.fullscreen.preferenceCollectAsState()
    if (LocalActivity.current?.hasDisplayCutout() == true && isFullscreen) {
        CheckboxItem(
            label = stringResource(MR.strings.pref_cutout_short),
            pref = screenModel.preferences.drawUnderCutout,
        )
    }

    CheckboxItem(
        label = stringResource(MR.strings.pref_keep_screen_on),
        pref = screenModel.preferences.keepScreenOn,
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_read_with_long_tap),
        pref = screenModel.preferences.readWithLongTap,
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_always_show_chapter_transition),
        pref = screenModel.preferences.alwaysShowChapterTransition,
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_page_transitions),
        pref = screenModel.preferences.pageTransitions,
    )

    val mangaSrPrefs = screenModel.rememberMangaSrPreferences()

    if (mangaSrPrefs != null) {
        val useGlobal by mangaSrPrefs.useGlobal.preferenceCollectAsState()

        LaunchedEffect(useGlobal) {
            screenModel.applyEffectiveSrSettings()
        }

        Text(
            text = if (useGlobal) {
                stringResource(MR.strings.pref_sr_mode_global)
            } else {
                stringResource(MR.strings.pref_sr_mode_manga)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            color = if (useGlobal) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
            style = MaterialTheme.typography.labelSmall,
        )

        if (useGlobal) {
            CheckboxItem(
                label = stringResource(MR.strings.pref_sr_enabled),
                pref = screenModel.preferences.srEnabled,
            )

            val srEnabled by screenModel.preferences.srEnabled.preferenceCollectAsState()
            val srModel by screenModel.preferences.srModel.preferenceCollectAsState()
            val srDenoiseLevel by screenModel.preferences.srDenoiseLevel.preferenceCollectAsState()

            LaunchedEffect(srEnabled, srModel, srDenoiseLevel) {
                screenModel.applyEffectiveSrSettings()
            }
            if (srEnabled) {
                HeadingItem(stringResource(MR.strings.sr_model_heading))
                FlowRow(
                    modifier = Modifier.padding(start = 24.dp, top = 0.dp, end = 24.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SRModel.entries.map { model ->
                        FilterChip(
                            selected = srModel == model.key,
                            onClick = { screenModel.preferences.srModel.set(model.key) },
                            label = { Text(stringResource(model.displayNameRes)) },
                        )
                    }
                }

                HeadingItem(stringResource(MR.strings.pref_sr_noise_level))
                FlowRow(
                    modifier = Modifier.padding(start = 24.dp, top = 0.dp, end = 24.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DenoiseLevel.entries.map { level ->
                        FilterChip(
                            selected = srDenoiseLevel == level.key,
                            onClick = { screenModel.preferences.srDenoiseLevel.set(level.key) },
                            label = {
                                Text(
                                    when (level) {
                                        DenoiseLevel.OFF -> stringResource(MR.strings.sr_denoise_level_off)
                                        DenoiseLevel.LIGHT -> stringResource(MR.strings.sr_denoise_level_light)
                                        DenoiseLevel.STRONG -> stringResource(MR.strings.sr_denoise_level_strong)
                                    },
                                )
                            },
                        )
                    }
                }

                HeadingItem(stringResource(MR.strings.sr_quality_heading))
                FlowRow(
                    modifier = Modifier.padding(start = 24.dp, top = 0.dp, end = 24.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Quality.entries.map { quality ->
                        FilterChip(
                            selected = screenModel.preferences.srQuality.get() == quality.key,
                            onClick = { screenModel.preferences.srQuality.set(quality.key) },
                            label = {
                                Text(
                                    when (quality) {
                                        Quality.FAST -> stringResource(MR.strings.sr_quality_fast)
                                        Quality.BALANCED -> stringResource(MR.strings.sr_quality_balanced)
                                        Quality.HIGH -> stringResource(MR.strings.sr_quality_high)
                                    },
                                )
                            },
                        )
                    }
                }

                SrIndicatorSettings(screenModel.preferences)
            }
        } else {
            CheckboxItem(
                label = stringResource(MR.strings.pref_sr_enabled),
                pref = mangaSrPrefs.srEnabled,
            )

            val srEnabled by mangaSrPrefs.srEnabled.preferenceCollectAsState()
            val srModel by mangaSrPrefs.srModel.preferenceCollectAsState()
            val srDenoiseLevel by mangaSrPrefs.srDenoiseLevel.preferenceCollectAsState()

            LaunchedEffect(srEnabled, srModel, srDenoiseLevel) {
                screenModel.applyEffectiveSrSettings()
            }

            if (srEnabled) {
                HeadingItem(stringResource(MR.strings.sr_model_heading))
                FlowRow(
                    modifier = Modifier.padding(start = 24.dp, top = 0.dp, end = 24.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SRModel.entries.map { model ->
                        FilterChip(
                            selected = srModel == model.key,
                            onClick = { mangaSrPrefs.srModel.set(model.key) },
                            label = { Text(stringResource(model.displayNameRes)) },
                        )
                    }
                }

                HeadingItem(stringResource(MR.strings.pref_sr_noise_level))
                FlowRow(
                    modifier = Modifier.padding(start = 24.dp, top = 0.dp, end = 24.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DenoiseLevel.entries.map { level ->
                        FilterChip(
                            selected = srDenoiseLevel == level.key,
                            onClick = { mangaSrPrefs.srDenoiseLevel.set(level.key) },
                            label = {
                                Text(
                                    when (level) {
                                        DenoiseLevel.OFF -> stringResource(MR.strings.sr_denoise_level_off)
                                        DenoiseLevel.LIGHT -> stringResource(MR.strings.sr_denoise_level_light)
                                        DenoiseLevel.STRONG -> stringResource(MR.strings.sr_denoise_level_strong)
                                    },
                                )
                            },
                        )
                    }
                }

                HeadingItem(stringResource(MR.strings.sr_quality_heading))
                FlowRow(
                    modifier = Modifier.padding(start = 24.dp, top = 0.dp, end = 24.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Quality.entries.map { quality ->
                        FilterChip(
                            selected = screenModel.preferences.srQuality.get() == quality.key,
                            onClick = { screenModel.preferences.srQuality.set(quality.key) },
                            label = {
                                Text(
                                    when (quality) {
                                        Quality.FAST -> stringResource(MR.strings.sr_quality_fast)
                                        Quality.BALANCED -> stringResource(MR.strings.sr_quality_balanced)
                                        Quality.HIGH -> stringResource(MR.strings.sr_quality_high)
                                    },
                                )
                            },
                        )
                    }
                }

                SrIndicatorSettings(screenModel.preferences)
            }
        }

        CheckboxItem(
            label = stringResource(MR.strings.pref_sr_use_global),
            pref = mangaSrPrefs.useGlobal,
        )
    } else {
        CheckboxItem(
            label = stringResource(MR.strings.pref_sr_enabled),
            pref = screenModel.preferences.srEnabled,
        )

        val srEnabled by screenModel.preferences.srEnabled.preferenceCollectAsState()
        if (srEnabled) {
            val srModel by screenModel.preferences.srModel.preferenceCollectAsState()
            HeadingItem(stringResource(MR.strings.sr_model_heading))
            FlowRow(
                modifier = Modifier.padding(start = 24.dp, top = 0.dp, end = 24.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SRModel.entries.map { model ->
                    FilterChip(
                        selected = srModel == model.key,
                        onClick = { screenModel.preferences.srModel.set(model.key) },
                        label = { Text(stringResource(model.displayNameRes)) },
                    )
                }
            }

            val srDenoiseLevel by screenModel.preferences.srDenoiseLevel.preferenceCollectAsState()
            HeadingItem(stringResource(MR.strings.pref_sr_noise_level))
            FlowRow(
                modifier = Modifier.padding(start = 24.dp, top = 0.dp, end = 24.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DenoiseLevel.entries.map { level ->
                        FilterChip(
                            selected = srDenoiseLevel == level.key,
                            onClick = { screenModel.preferences.srDenoiseLevel.set(level.key) },
                            label = {
                                Text(
                                    when (level) {
                                        DenoiseLevel.OFF -> stringResource(MR.strings.sr_denoise_level_off)
                                        DenoiseLevel.LIGHT -> stringResource(MR.strings.sr_denoise_level_light)
                                        DenoiseLevel.STRONG -> stringResource(MR.strings.sr_denoise_level_strong)
                                    },
                                )
                            },
                        )
                    }
            }

            HeadingItem(stringResource(MR.strings.sr_quality_heading))
            FlowRow(
                modifier = Modifier.padding(start = 24.dp, top = 0.dp, end = 24.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Quality.entries.map { quality ->
                    FilterChip(
                        selected = screenModel.preferences.srQuality.get() == quality.key,
                        onClick = { screenModel.preferences.srQuality.set(quality.key) },
                        label = {
                            Text(
                                when (quality) {
                                    Quality.FAST -> stringResource(MR.strings.sr_quality_fast)
                                    Quality.BALANCED -> stringResource(MR.strings.sr_quality_balanced)
                                    Quality.HIGH -> stringResource(MR.strings.sr_quality_high)
                                },
                            )
                        },
                    )
                }
            }

            SrIndicatorSettings(screenModel.preferences)
        }
    }

    CheckboxItem(
        label = stringResource(MR.strings.pref_flash_page),
        pref = screenModel.preferences.flashOnPageChange,
    )
    if (flashPageState) {
        SliderItem(
            value = flashMillis / ReaderPreferences.MILLI_CONVERSION,
            valueRange = 1..15,
            label = stringResource(MR.strings.pref_flash_duration),
            valueString = stringResource(MR.strings.pref_flash_duration_summary, flashMillis),
            onChange = { flashMillisPref.set(it * ReaderPreferences.MILLI_CONVERSION) },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        SliderItem(
            value = flashInterval,
            valueRange = 1..10,
            label = stringResource(MR.strings.pref_flash_page_interval),
            valueString = pluralStringResource(MR.plurals.pref_pages, flashInterval, flashInterval),
            onChange = {
                flashIntervalPref.set(it)
            },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        SettingsChipRow(MR.strings.pref_flash_with) {
            flashColors.map { (labelRes, value) ->
                FilterChip(
                    selected = flashColor == value,
                    onClick = { flashColorPref.set(value) },
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.SrIndicatorSettings(
    preferences: ReaderPreferences,
) {
    val srIndicatorPosition by preferences.srIndicatorPosition.preferenceCollectAsState()
    val srIndicatorMode by preferences.srIndicatorMode.preferenceCollectAsState()

    HeadingItem(stringResource(MR.strings.pref_sr_indicator_position))
    FlowRow(
        modifier = Modifier.padding(start = 24.dp, top = 0.dp, end = 24.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SRIndicatorPosition.entries.map { position ->
            FilterChip(
                selected = srIndicatorPosition == position.key,
                onClick = { preferences.srIndicatorPosition.set(position.key) },
                label = {
                    Text(
                        when (position) {
                            SRIndicatorPosition.TOP_LEFT -> stringResource(MR.strings.sr_indicator_position_top_left)
                            SRIndicatorPosition.TOP_CENTER -> stringResource(MR.strings.sr_indicator_position_top_center)
                            SRIndicatorPosition.TOP_RIGHT -> stringResource(MR.strings.sr_indicator_position_top_right)
                            SRIndicatorPosition.BOTTOM_LEFT -> stringResource(MR.strings.sr_indicator_position_bottom_left)
                            SRIndicatorPosition.BOTTOM_CENTER -> stringResource(MR.strings.sr_indicator_position_bottom_center)
                            SRIndicatorPosition.BOTTOM_RIGHT -> stringResource(MR.strings.sr_indicator_position_bottom_right)
                        },
                    )
                },
            )
        }
    }

    HeadingItem(stringResource(MR.strings.pref_sr_indicator_mode))
    FlowRow(
        modifier = Modifier.padding(start = 24.dp, top = 0.dp, end = 24.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SRIndicatorDisplayMode.entries.map { mode ->
            FilterChip(
                selected = srIndicatorMode == mode.key,
                onClick = { preferences.srIndicatorMode.set(mode.key) },
                label = {
                    Text(
                        when (mode) {
                            SRIndicatorDisplayMode.HIDDEN -> stringResource(MR.strings.sr_indicator_mode_hidden)
                            SRIndicatorDisplayMode.ICON_ONLY -> stringResource(MR.strings.sr_indicator_mode_icon)
                            SRIndicatorDisplayMode.ICON_AND_TEXT -> stringResource(MR.strings.sr_indicator_mode_icon_text)
                        },
                    )
                },
            )
        }
    }
}
