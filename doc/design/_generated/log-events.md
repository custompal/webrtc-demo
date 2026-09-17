# Log events (Kotlin AppLog + C++ NLOG_*)

> **GENERATED — do not edit.** Regenerate with `bash scripts/gen-doc-tables.sh`.
> Generator: `scripts/gen-doc-tables.sh` (sha256 `e7951ef4e950858588103cb1e5714d390612fd8db625f570759c20e5c8c156c5`)
> Deterministic: no timestamp is embedded, so repeated runs are byte-identical.
> Sources (sha256 of the exact revision this table was built from):
> - `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingMessage.kt` — sha256 `4fd325e1f0c0646b50b719c94750c459467e9957d43151f68c1b104a07f3c9b1`
> - `app/src/main/cpp/log/log_macros.h` — sha256 `c24d023e30dc4d4e095bc67006e08732397837bb7ade00b1ee5507cdd7478610`

## 1. Kotlin event keys (`AppLog.<level>(TAG, "key", ...)`)

Source: `app/src/main/kotlin/**/*.kt` (event key = second call argument, as emitted).

| Level | Event key | Source | Field keys |
|---|---|---|---|
| i | `main_activity_create` | `app/src/main/kotlin/com/example/webrtcdemo/MainActivity.kt:29` | - |
| i | `main_activity_resume` | `app/src/main/kotlin/com/example/webrtcdemo/MainActivity.kt:45` | - |
| i | `main_activity_pause` | `app/src/main/kotlin/com/example/webrtcdemo/MainActivity.kt:49` | - |
| i | `app_create` | `app/src/main/kotlin/com/example/webrtcdemo/WebRtcDemoApp.kt:40` | log_dir,level,max_bytes,max_files |
| w | `native_log_init_skipped` | `app/src/main/kotlin/com/example/webrtcdemo/WebRtcDemoApp.kt:62` | reason |
| e | `uncaught_exception` | `app/src/main/kotlin/com/example/webrtcdemo/WebRtcDemoApp.kt:82` | - |
| e | `log_sink_degraded` | `app/src/main/kotlin/com/example/webrtcdemo/WebRtcDemoApp.kt:118` | reason,file,initialized,write_failures |
| w | `signaling_url_rejected` | `app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:84` | override,expected_path |
| i | `export_zip` | `app/src/main/kotlin/com/example/webrtcdemo/diag/LogExporter.kt:179` | zip,files,bytes |
| w | `export_cleanup_failed` | `app/src/main/kotlin/com/example/webrtcdemo/diag/LogExporter.kt:191` | zip |
| e | `export_failed` | `app/src/main/kotlin/com/example/webrtcdemo/diag/LogExporter.kt:193` | reason |
| i | `export_cleanup` | `app/src/main/kotlin/com/example/webrtcdemo/diag/LogExporter.kt:260` | deleted |
| w | `encoder_fallback_switch_skipped` | `app/src/main/kotlin/com/example/webrtcdemo/encoder/FallbackVideoEncoderSelector.kt:88` | reason,codec |
| i | `encoder_fallback_switch_signal` | `app/src/main/kotlin/com/example/webrtcdemo/encoder/FallbackVideoEncoderSelector.kt:95` | trigger,value,codec,mech |
| e | `encoder_init_failed` | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:122` | stage |
| e | `encoder_init_failed` | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:138` | stage,rc |
| i | `encoder_init` | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:150` | impl,w,h,s,t,cpu |
| w | `to_i420_failed` | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:184` | reason |
| e | `encoded_plane_rejected` | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:199` | reason,w,h,stride_y,stride_u,stride_v,direct |
| e | `encoder_encode_failed` | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:233` | rc |
| w | `encoder_slow_frame` | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:244` | ms |
| w | `setrates_failed` | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:286` | rc,s,t,len,total_bps,fps |
| i | `setrates` | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:300` | total_bps,fps,s,t |
| i | `encoder_released` | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:323` | - |
| e | `encoded_frame_too_large` | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:377` | bytes,max |
| d | `encoded_frame` | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:414` | bytes,cap,w,h,key,qp |
| i | `encoder_created` | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoderFactory.kt:61` | impl,codec,mech |
| w | `encoder_fallback_unavailable` | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoderFactory.kt:74` | reason,codec |
| i | `encoder_created` | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoderFactory.kt:80` | impl,codec,profile |
| i | `log_level_changed` | `app/src/main/kotlin/com/example/webrtcdemo/log/FileLogger.kt:416` | from,to |
| w | `nat_start_skipped` | `app/src/main/kotlin/com/example/webrtcdemo/nat/NatTypeRepository.kt:61` | reason |
| w | `nat_start_skipped` | `app/src/main/kotlin/com/example/webrtcdemo/nat/NatTypeRepository.kt:68` | reason,stun |
| i | `nat_start` | `app/src/main/kotlin/com/example/webrtcdemo/nat/NatTypeRepository.kt:75` | host,port |
| i | `peer_nat_received` | `app/src/main/kotlin/com/example/webrtcdemo/nat/NatTypeRepository.kt:106` | nat |
| i | `nat_done` | `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeCallbacks.kt:37` | nat,detail |
| i | `native_lib_loaded` | `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeLoader.kt:45` | lib |
| e | `native_lib_load_failed` | `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeLoader.kt:49` | lib |
| w | `native_log_init_failed` | `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeLog.kt:112` | reason,dir |
| w | `native_log_init_failed` | `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeLog.kt:116` | reason |
| i | `native_log_init` | `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeLog.kt:123` | dir,base,level,max_bytes,max_files |
| w | `native_log_init_failed` | `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeLog.kt:137` | reason |
| w | `native_log_set_level_failed` | `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeLog.kt:156` | reason |
| w | `native_log_flush_failed` | `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeLog.kt:168` | reason |
| i | `ws_open` | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:272` | url |
| e | `msg_decode_failed` | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:284` | bytes |
| i | `ws_closing` | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:308` | code,reason |
| i | `ws_close` | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:313` | code,reason |
| w | `ws_close` | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:321` | reason,code |
| i | `offer_sent` | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:375` | sdp_bytes |
| i | `answer_sent` | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:383` | sdp_bytes |
| i | `ws_connecting` | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:445` | url |
| i | `room_create_sent` | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:454` | - |
| i | `room_join_sent` | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:461` | room |
| w | `msg_blocked` | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:469` | state,reason |
| w | `msg_dropped` | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:476` | reason,type |
| e | `msg_encode_failed` | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:482` | type |
| w | `msg_dropped` | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:487` | reason,type |
| d | `msg_sent` | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:492` | type,bytes |
| w | `ws_reconnect_suppressed` | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:523` | code |
| w | `disconnect_cause` | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:533` | cause,code,rejoin,room |
| w | `server_error_surfaced` | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:547` | code |
| w | `disconnect_cause` | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:558` | cause,peer_left |
| i | `room_created` | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:587` | room |
| i | `room_joined` | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:588` | room,peer |
| i | `peer_joined` | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:594` | peer |
| i | `peer_left` | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:595` | peer |
| i | `offer_received` | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:596` | sdp_bytes |
| i | `answer_received` | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:597` | sdp_bytes |
| d | `ice_received` | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:598` | candidate_bytes |
| i | `nat_received` | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:599` | nat |
| e | `server_error` | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:600` | code,detail |
| v | `pong_received` | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:606` | - |
| d | `msg_received` | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:607` | type |
| w | `pong_miss` | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:634` | count,tolerance,timeout_ms,fail_after_ms |
| w | `ws_pong_timeout` | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:645` | timeout_ms,misses |
| e | `ws_rejoin_give_up` | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:684` | attempts,budget_ms,room |
| w | `ws_rejoin_retry` | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:705` | attempt,delay_ms,room |
| i | `ws_reconnect_skipped` | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:723` | reason |
| e | `ws_reconnect_give_up` | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:731` | attempts,budget_ms,in_room_before_drop,room |
| w | `ws_reconnect_scheduled` | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:749` | attempt,reason,delay_ms,budget_ms,max_attempts |
| i | `state_change` | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:768` | from,to |
| i | `on_pause` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallScreen.kt:179` | release,reason |
| i | `on_resume` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallScreen.kt:185` | tick |
| i | `preview_recover_armed` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallScreen.kt:200` | localSurface,remoteSurface,localReleased |
| w | `preview_recover_attempt` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallScreen.kt:221` | attempt,localFrame,remoteFrame,localSurface,localReleased |
| i | `preview_recover_reattached` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallScreen.kt:245` | attempt,localFrame,remoteFrame |
| e | `preview_recover_give_up` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallScreen.kt:257` | attempts,localSurface |
| i | `export_zip` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallScreen.kt:417` | from |
| i | `remote_frame_liveness` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:313` | source,age_ms,frames,down_bps,phase,view,seq,session |
| i | `call_init_ignored` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:347` | reason,room,role,seq,session,pc_ready |
| w | `retry_invoked` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:380` | result,reason,room,role,seq |
| i | `retry_invoked` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:393` | room,role,phase,reason,elapsed_ms,retry,seq,session |
| w | `session_retry` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:422` | seq,room,role |
| i | `ui_conn_state_enter` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:461` | phase,reason,room,role,seq |
| i | `retry_reoffer` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:524` | role,mode,peer_known,seq |
| i | `retry_reoffer` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:536` | role,mode,delay_ms,seq |
| i | `call_init` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:584` | room,role,reason,seq,session,pc_ready |
| i | `hangup` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:623` | seq,session |
| w | `remote_frame_ignored` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:657` | reason,phase,seq,session |
| w | `room_recreate_invoked` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:693` | result,reason |
| w | `room_recreate_invoked` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:699` | result,reason,room,role |
| w | `room_recreate_invoked` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:706` | room,role,media_alive,seq,session |
| w | `signaling_lost action=keep_call` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:770` | ever_connected,media_alive,media_age_ms,phase,seq,session |
| w | `signaling_lost action=end_call` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:798` | reason,phase,seq,session |
| w | `disconnect_deferred` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:813` | cause,ever_connected,media_alive,phase,seq,session |
| i | `room_recreated` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:837` | room,recreate,seq,session |
| i | `rejoined` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:869` | room |
| i | `peer_joined` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:886` | peer |
| i | `peer_left` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:900` | - |
| w | `peer_left action=keep_call` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:912` | ever_connected,media_alive,media_age_ms,seq,session |
| w | `peer_left action=end_call` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:935` | reason,seq,session |
| i | `rejoin_offer_received` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:960` | waited_ms,seq,session |
| w | `remote_deferred` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:976` | kind,seq,queued,sdp_bytes |
| w | `remote_deferred` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1002` | kind,seq,queued,sdp_bytes |
| i | `remote_deferred` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1023` | kind,seq,queued,mid |
| w | `rejoin_room_full` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1046` | code |
| e | `server_error` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1051` | code |
| w | `room_not_found action=keep_call` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1062` | code,media_alive,media_age_ms,rejoin_context,rejoin_drop,phase,seq,session |
| w | `ice_flap_suppressed` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1144` | detail,media_source,age_ms,seq,session |
| i | `ice_relay_state` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1165` | relay,turn_errors,filtered_loopback,relay_missing,seq,session |
| w | `ice_down_ui_suppressed` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1226` | age_ms,media_source,pair,phase,message,seq,session |
| i | `session_release` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1247` | reason,seq,session |
| i | `session_teardown` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1279` | reason,session |
| i | `retry_clock_started` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1414` | trigger,waited_ms,role,seq,session |
| i | `retry_clock_reset` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1436` | reason,role,seq,session |
| i | `ui_conn_state` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1475` | phase,reason,elapsed_ms,pair,frame,stalled,media_source,media_age_ms,ice_down,retry,seq,session |
| w | `ice_down_ui_suppressed` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1502` | age_ms,media_source,pair,phase,reason,ice_down,seq,session |
| w | `ice_regather_invoked` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1543` | reason,accepted,local_relay,turn_errors,filtered_loopback,elapsed_ms,seq,session |
| w | `no_selected_pair` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1582` | after_ms,pair,impl,avail_bps,up_bps,down_bps,local,remote,seq,session |
| i | `retry_reoffer_run` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1618` | role,delay_ms,seq,session |
| i | `remote_replay_done` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1642` | offer,answer,candidates,source |
| i | `remote_replay_done` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1670` | offer,answer,candidates,source,order |
| w | `answer_timeout` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1701` | reason,waited_ms,role,seq,session |
| e | `call_failed` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1718` | reason |
| i | `ice_error_cleared` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1745` | reason,phase,media_source,media_age_ms,pair,banner,seq,session |
| w | `call_end` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1791` | notice |
| i | `export_zip` | `app/src/main/kotlin/com/example/webrtcdemo/ui/home/HomeScreen.kt:176` | from |
| i | `permission_result` | `app/src/main/kotlin/com/example/webrtcdemo/ui/home/HomeViewModel.kt:103` | granted |
| i | `session_start` | `app/src/main/kotlin/com/example/webrtcdemo/ui/home/HomeViewModel.kt:138` | url,create,room |
| e | `server_error` | `app/src/main/kotlin/com/example/webrtcdemo/ui/home/HomeViewModel.kt:186` | code |
| e | `pc_start_rejected` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:283` | reason,phase |
| i | `pc_starting` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:306` | ice_servers,force_relay,turn_tcp |
| e | `pc_create_failed` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:330` | - |
| w | `video_track_missing` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:348` | - |
| i | `pc_local_tracks` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:352` | video,audio |
| w | `pc_start_aborted` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:377` | reason |
| w | `pc_start_aborted` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:386` | reason |
| i | `pc_created` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:389` | phase |
| e | `offer_create_rejected` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:407` | reason,phase |
| i | `offer_create` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:415` | - |
| i | `offer_created` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:420` | sdp_bytes,candidates |
| i | `offer_sent` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:430` | - |
| e | `offer_create_failed` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:436` | reason |
| e | `offer_set_failed` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:441` | reason |
| i | `offer_received` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:457` | sdp_bytes |
| e | `offer_dropped` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:466` | reason |
| w | `offer_deferred` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:469` | reason,start_in_flight,sdp_bytes |
| i | `answer_create` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:493` | - |
| i | `answer_created` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:497` | sdp_bytes,candidates |
| i | `answer_sent` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:507` | - |
| e | `answer_create_failed` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:513` | reason |
| e | `answer_set_failed` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:518` | reason |
| e | `remote_offer_set_failed` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:529` | reason |
| i | `answer_received` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:543` | sdp_bytes,candidates |
| e | `answer_dropped` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:559` | reason |
| w | `answer_deferred` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:562` | reason,start_in_flight |
| w | `ice_dropped` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:597` | reason |
| w | `ice_candidate_filtered` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:607` | direction,reason,remote,n |
| w | `ice_dropped` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:637` | reason,remote |
| w | `ice_deferred` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:643` | remote,queued,start_in_flight |
| i | `ice_candidate_remote` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:687` | remote,total,via_replay,sdp_remote |
| i | `ice_candidate_remote_total trickled=${tallied} sdp=${sdpRemoteCandidateCount} ` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:705` | trigger |
| i | `audio_toggle` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:716` | enabled |
| i | `video_toggle` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:723` | enabled |
| i | `pc_closed` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:763` | signaling_before,stats_loop,watchdog,phase |
| w | `pc_close_failed` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:839` | reason |
| w | `pc_dispose_failed` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:844` | reason |
| d | `local_description_set` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:852` | - |
| e | `local_description_set_failed` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:858` | reason |
| d | `remote_description_set` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:867` | - |
| e | `remote_description_set_failed` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:873` | reason |
| w | `ice_candidate_filtered` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:888` | direction,reason,local,n |
| i | `ice_candidate_local` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:900` | local,mid,idx |
| i | `ice_gathering_complete` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:939` | local,relay,turn_configured,turn_tcp,turn_errors,filtered_loopback |
| w | `ice_turn_error` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:984` | code,text,url,count,local_relay |
| d | `signaling_state` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1003` | state |
| d | `ice_receiving` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1007` | receiving |
| d | `ice_candidates_removed` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1011` | count |
| i | `remote_stream_added` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1015` | id |
| i | `remote_stream_removed` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1019` | id |
| d | `data_channel` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1023` | label |
| d | `renegotiation_needed` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1027` | - |
| i | `remote_track_added` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1032` | kind |
| i | `offer_replayed` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1073` | sdp_bytes |
| i | `answer_replayed` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1077` | sdp_bytes |
| i | `ice_replayed` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1081` | count |
| i | `remote_replay_done` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1088` | offer,answer,candidates |
| i | `ice_watchdog_started` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1125` | timeout_ms,fail_ms,turn_configured |
| w | `ice_watchdog_stale_tier` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1165` | tier_ms,elapsed_ms,action,watchdog_active,ice_state |
| w | `ice_watchdog_rearmed` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1184` | after_ms,next_ms,reason,turn_configured |
| w | `ice_timeout_restarting` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1225` | - |
| e | `ice_timeout` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1233` | - |
| w | `ice_not_connected` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1253` | - |
| w | `ice_restart_exhausted` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1275` | reason,attempts |
| w | `ice_restart_failed` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1289` | reason |
| w | `ice_regather_failed` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1299` | reason |
| i | `ice_restart_requested` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1302` | reason,attempt,accepted,local_relay,turn_errors |
| i | `ice_watchdog_rearmed reason=relay_candidate skipped=no_watchdog` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1346` | elapsed_ms,relay,candidate,watchdog_started |
| i | `ice_watchdog_rearmed` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1359` | reason,elapsed_ms,relay,candidate |
| i | `ice_watchdog_ok` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1380` | elapsed_ms |
| w | `stats_failed` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1417` | reason |
| w | `vp9_not_advertised` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1432` | codecs |
| i | `codec_preferences_set` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1440` | codec,count |
| w | `codec_preferences_failed` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1442` | reason |
| d | `capture_frame` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/FrameNormalizer.kt:56` | buf,w,h,rot |
| w | `frame_convert_failed` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/FrameNormalizer.kt:77` | reason |
| d | `capture_frame` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/FrameNormalizer.kt:91` | buf,convert_us,w,h,rot |
| i | `capturer_started` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/FrameNormalizer.kt:107` | success |
| i | `capturer_stopped` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/FrameNormalizer.kt:113` | - |
| w | `webrtc_log_throttled` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/LibwebrtcLoggable.kt:89` | dropped |
| e | `camera_error` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/MediaCapture.kt:89` | detail |
| e | `camera_disconnected` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/MediaCapture.kt:93` | - |
| w | `camera_freezed` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/MediaCapture.kt:97` | detail |
| i | `camera_opening` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/MediaCapture.kt:101` | camera |
| i | `camera_first_frame` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/MediaCapture.kt:105` | - |
| i | `camera_closed` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/MediaCapture.kt:109` | - |
| i | `capture_already_started` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/MediaCapture.kt:124` | facing |
| e | `camera_missing` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/MediaCapture.kt:133` | - |
| i | `capture_low_res` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/MediaCapture.kt:143` | w,h,fps |
| e | `camera_capturer_null` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/MediaCapture.kt:153` | device |
| i | `capture_started` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/MediaCapture.kt:168` | device,w,h,fps,facing |
| i | `camera_switched` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/MediaCapture.kt:192` | facing |
| i | `capture_resume_noop` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/MediaCapture.kt:214` | - |
| w | `capture_resume_start` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/MediaCapture.kt:217` | - |
| w | `capture_stop_failed` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/MediaCapture.kt:227` | reason |
| i | `capture_released` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/MediaCapture.kt:237` | - |
| d | `pc_signaling_state` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/PeerConnectionObserverImpl.kt:82` | state |
| i | `pc_ice_connection_state` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/PeerConnectionObserverImpl.kt:87` | state |
| i | `pc_ice_gathering_state` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/PeerConnectionObserverImpl.kt:96` | state |
| i | `pc_add_track` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/PeerConnectionObserverImpl.kt:125` | kind |
| e | `pc_connection_state` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/PeerConnectionObserverImpl.kt:143` | - |
| i | `pc_connection_state` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/PeerConnectionObserverImpl.kt:145` | - |
| i | `selected_candidate_pair` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/PeerConnectionObserverImpl.kt:154` | local,remote,mode,reason,last_data_ms |
| w | `pc_ice_candidate_error` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/PeerConnectionObserverImpl.kt:170` | url,address,port,code,text |
| i | `stats_sample` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/StatsMapper.kt:87` | mode,up_bps,down_bps,avail_bps,impl,local,remote |
| w | `renderer_recreate_skipped` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:149` | which,reason |
| i | `renderer_recreate` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:152` | which |
| i | `remote_first_frame` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:163` | which,surface |
| d | `remote_resolution_changed` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:173` | w,h,rot,surface |
| i | `surface_created` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:197` | which |
| d | `surface_changed` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:201` | which,w,h |
| i | `surface_destroyed` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:210` | which |
| i | `renderer_created` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:216` | which,mirror,tracked |
| w | `renderer_attach_rejected` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:230` | which,reason |
| i | `renderer_attached` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:236` | which,surface |
| w | `renderer_attach_rejected` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:245` | which,reason |
| w | `renderer_on_frame_failed` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:256` | which,reason |
| i | `renderer_attached` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:263` | which,surface,frame_sink |
| w | `renderer_detach_failed` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:285` | which,reason |
| i | `renderer_detached` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:287` | which |
| w | `renderer_detach_failed` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:306` | which,reason |
| i | `renderer_detached` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:308` | which |
| w | `renderer_release_failed` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:330` | reason |
| i | `renderer_released` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:334` | which,releasedTotal |
| i | `rtc_config` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:175` | stun,turn,force_relay,turn_tcp,ice_servers |
| e | `engine_init_skipped` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:104` | reason |
| e | `jni_binding_missing` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:115` | cls |
| w | `encoder_fallback` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:147` | reason |
| i | `field_trials_set` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:164` | frame_dropper |
| i | `engine_ready` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:176` | impl,use_default_encoder,encoder_mode,encoder_fallback |
| e | `engine_init_failed` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:208` | - |
| i | `engine_shutdown` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:284` | - |

## 2. Native event keys (`NLOG_<LEVEL>(tag, "key ...", ...)`)

Source: `app/src/main/cpp/**/*.cpp` (event key = first whitespace-delimited token of the format string).

| Level | Tag | Event key | Source |
|---|---|---|---|
| INFO | `kTagEncoder` | `encoder_cpu_probe` | `app/src/main/cpp/encoder/vp9_encoder.cpp:96` |
| ERROR | `kTagEncoder` | `encode_raw_img_alloc_failed` | `app/src/main/cpp/encoder/vp9_encoder.cpp:216` |
| INFO | `kTagEncoder` | `encoder_raw_img_alloc` | `app/src/main/cpp/encoder/vp9_encoder.cpp:222` |
| ERROR | `kTagEncoder` | `encoder_init_bad_size` | `app/src/main/cpp/encoder/vp9_encoder.cpp:318` |
| ERROR | `kTagEncoder` | `encoder_init_rejected` | `app/src/main/cpp/encoder/vp9_encoder.cpp:324` |
| ERROR | `kTagEncoder` | `encoder_init_rejected` | `app/src/main/cpp/encoder/vp9_encoder.cpp:331` |
| ERROR | `kTagEncoder` | `encoder_init_failed` | `app/src/main/cpp/encoder/vp9_encoder.cpp:359` |
| ERROR | `kTagEncoder` | `encoder_init_failed` | `app/src/main/cpp/encoder/vp9_encoder.cpp:379` |
| INFO | `kTagEncoder` | `encoder_threads` | `app/src/main/cpp/encoder/vp9_encoder.cpp:394` |
| INFO | `kTagEncoder` | `encoder_init` | `app/src/main/cpp/encoder/vp9_encoder.cpp:407` |
| INFO | `kTagBitrate` | `ts_target_kbps` | `app/src/main/cpp/encoder/vp9_encoder.cpp:412` |
| WARN | `kTagBitrate` | `setrates_rejected` | `app/src/main/cpp/encoder/vp9_encoder.cpp:427` |
| ERROR | `kTagBitrate` | `setrates_rejected` | `app/src/main/cpp/encoder/vp9_encoder.cpp:432` |
| INFO | `kTagBitrate` | `setrates` | `app/src/main/cpp/encoder/vp9_encoder.cpp:438` |
| INFO | `kTagBitrate` | `layer_bps` | `app/src/main/cpp/encoder/vp9_encoder.cpp:441` |
| WARN | `kTagBitrate` | `encoder_rate_floor` | `app/src/main/cpp/encoder/vp9_encoder.cpp:468` |
| INFO | `kTagBitrate` | `encoder_rates` | `app/src/main/cpp/encoder/vp9_encoder.cpp:477` |
| INFO | `kTagBitrate` | `encoder_rates` | `app/src/main/cpp/encoder/vp9_encoder.cpp:486` |
| ERROR | `kTagBitrate` | `setrates_config_set_failed` | `app/src/main/cpp/encoder/vp9_encoder.cpp:495` |
| INFO | `kTagBitrate` | `ts_target_kbps` | `app/src/main/cpp/encoder/vp9_encoder.cpp:500` |
| INFO | `kTagBitrate` | `<non-literal>` | `app/src/main/cpp/encoder/vp9_encoder.cpp:504` |
| ERROR | `kTagEncoder` | `copy_encoded_frame_size_mismatch` | `app/src/main/cpp/encoder/vp9_encoder.cpp:539` |
| ERROR | `kTagEncoder` | `encode_bad_frame` | `app/src/main/cpp/encoder/vp9_encoder.cpp:573` |
| WARN | `kTagEncoder` | `encode_odd_size` | `app/src/main/cpp/encoder/vp9_encoder.cpp:581` |
| WARN | `kTagEncoder` | `encode_bad_rotation` | `app/src/main/cpp/encoder/vp9_encoder.cpp:601` |
| INFO | `kTagEncoder` | `encoder_rotation_mode` | `app/src/main/cpp/encoder/vp9_encoder.cpp:606` |
| ERROR | `kTagEncoder` | `encode_resize_reinit_failed` | `app/src/main/cpp/encoder/vp9_encoder.cpp:637` |
| INFO | `kTagEncoder` | `encoder_reinit` | `app/src/main/cpp/encoder/vp9_encoder.cpp:653` |
| ERROR | `kTagEncoder` | `encode_rotate_buf_invalid` | `app/src/main/cpp/encoder/vp9_encoder.cpp:671` |
| INFO | `kTagEncoder` | `encoder_rotate` | `app/src/main/cpp/encoder/vp9_encoder.cpp:711` |
| INFO | `kTagEncoder` | `encode_vpx_begin` | `app/src/main/cpp/encoder/vp9_encoder.cpp:752` |
| DEBUG | `kTagEncoder` | `encode_vpx_begin` | `app/src/main/cpp/encoder/vp9_encoder.cpp:763` |
| DEBUG | `kTagEncoder` | `encode_vpx_done` | `app/src/main/cpp/encoder/vp9_encoder.cpp:773` |
| ERROR | `kTagEncoder` | `encode_failed` | `app/src/main/cpp/encoder/vp9_encoder.cpp:778` |
| WARN | `kTagEncoder` | `encode_no_packet` | `app/src/main/cpp/encoder/vp9_encoder.cpp:824` |
| WARN | `kTagEncoder` | `encoded_frame` | `app/src/main/cpp/encoder/vp9_encoder.cpp:839` |
| DEBUG | `kTagEncoder` | `encoded_frame` | `app/src/main/cpp/encoder/vp9_encoder.cpp:846` |
| INFO | `kTagEncoder` | `encoder_perf` | `app/src/main/cpp/encoder/vp9_encoder.cpp:906` |
| ERROR | `kTag` | `callback_bridge_init_failed` | `app/src/main/cpp/jni/callback_bridge.cpp:47` |
| INFO | `kJniTag` | `jni_onload` | `app/src/main/cpp/jni/jni_bridge.cpp:105` |
| INFO | `kTag` | `jni_call` | `app/src/main/cpp/jni/nat_detector_jni.cpp:28` |
| ERROR | `kTag` | `nativeDetect_rejected` | `app/src/main/cpp/jni/nat_detector_jni.cpp:33` |
| INFO | `kTag` | `jni_call` | `app/src/main/cpp/jni/nat_detector_jni.cpp:43` |
| ERROR | `kTag` | `register_natives_failed` | `app/src/main/cpp/jni/nat_detector_jni.cpp:66` |
| DEBUG | `kTag` | `jni_call` | `app/src/main/cpp/jni/native_log_jni.cpp:35` |
| INFO | `kTag` | `jni_call` | `app/src/main/cpp/jni/native_log_jni.cpp:53` |
| DEBUG | `kTag` | `jni_call` | `app/src/main/cpp/jni/native_log_jni.cpp:60` |
| INFO | `kTag` | `jni_call` | `app/src/main/cpp/jni/native_log_jni.cpp:65` |
| ERROR | `kTag` | `register_natives_failed` | `app/src/main/cpp/jni/native_log_jni.cpp:90` |
| ERROR | `kTag` | `nativeCreate_failed` | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:82` |
| DEBUG | `kTag` | `jni_call` | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:90` |
| DEBUG | `kTag` | `jni_call` | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:100` |
| ERROR | `kTag` | `nativeInit_rejected` | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:110` |
| DEBUG | `kTag` | `jni_call` | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:132` |
| ERROR | `kTag` | `nativeEncode_rejected` | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:143` |
| ERROR | `kTag` | `nativeEncode_rejected` | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:151` |
| ERROR | `kTag` | `nativeEncode_rejected` | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:163` |
| ERROR | `kTag` | `nativeCopyEncodedFrame_rejected` | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:195` |
| ERROR | `kTag` | `nativeCopyEncodedFrame_rejected` | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:201` |
| DEBUG | `kTag` | `jni_return` | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:218` |
| DEBUG | `kTag` | `jni_call` | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:240` |
| ERROR | `kTag` | `nativeSetRates_rejected` | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:260` |
| ERROR | `kTag` | `nativeSetRates_rejected` | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:268` |
| ERROR | `kTag` | `nativeSetRates_rejected` | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:283` |
| INFO | `kTag` | `nativeSetRates_fold` | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:289` |
| INFO | `kTag` | `nativeSetRates_matrix` | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:296` |
| DEBUG | `kTag` | `jni_call` | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:330` |
| ERROR | `kTag` | `register_natives_failed` | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:376` |
| INFO | `kTag` | `nat_done` | `app/src/main/cpp/nat/nat_detector.cpp:130` |
| INFO | `kTag` | `nat_start` | `app/src/main/cpp/nat/nat_detector.cpp:141` |
| WARN | `kTag` | `nat_local_address_unknown` | `app/src/main/cpp/nat/nat_detector.cpp:160` |
| INFO | `kTag` | `nat_request` | `app/src/main/cpp/nat/nat_detector.cpp:168` |
| ERROR | `kTag` | `stun_socket_failed` | `app/src/main/cpp/nat/stun_client.cpp:196` |
| ERROR | `kTag` | `stun_bind_failed` | `app/src/main/cpp/nat/stun_client.cpp:214` |
| INFO | `kTag` | `stun_socket_ready` | `app/src/main/cpp/nat/stun_client.cpp:230` |
| INFO | `kTag` | `stun_local_address` | `app/src/main/cpp/nat/stun_client.cpp:269` |
| ERROR | `kTag` | `stun_resolve_failed` | `app/src/main/cpp/nat/stun_client.cpp:302` |
| DEBUG | `kTag` | `stun_request` | `app/src/main/cpp/nat/stun_client.cpp:349` |
| ERROR | `kTag` | `stun_sendto_failed` | `app/src/main/cpp/nat/stun_client.cpp:359` |
| ERROR | `kTag` | `stun_recvfrom_failed` | `app/src/main/cpp/nat/stun_client.cpp:382` |
| WARN | `kTag` | `stun_response_error` | `app/src/main/cpp/nat/stun_client.cpp:407` |
| DEBUG | `kTag` | `stun_response` | `app/src/main/cpp/nat/stun_client.cpp:438` |
| ERROR | `kTag` | `java_exception` | `app/src/main/cpp/util/jni_util.cpp:86` |

## 3. Event-key index (sorted, unique)

| Event key | Side | Emitted at |
|---|---|---|
| `<non-literal>` | Native | `app/src/main/cpp/encoder/vp9_encoder.cpp:504` |
| `answer_create` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:493` |
| `answer_create_failed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:513` |
| `answer_created` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:497` |
| `answer_deferred` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:562` |
| `answer_dropped` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:559` |
| `answer_received` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:597` |
| `answer_received` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:543` |
| `answer_replayed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1077` |
| `answer_sent` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:383` |
| `answer_sent` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:507` |
| `answer_set_failed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:518` |
| `answer_timeout` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1701` |
| `app_create` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/WebRtcDemoApp.kt:40` |
| `audio_toggle` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:716` |
| `call_end` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1791` |
| `call_failed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1718` |
| `call_init` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:584` |
| `call_init_ignored` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:347` |
| `callback_bridge_init_failed` | Native | `app/src/main/cpp/jni/callback_bridge.cpp:47` |
| `camera_capturer_null` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/MediaCapture.kt:153` |
| `camera_closed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/MediaCapture.kt:109` |
| `camera_disconnected` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/MediaCapture.kt:93` |
| `camera_error` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/MediaCapture.kt:89` |
| `camera_first_frame` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/MediaCapture.kt:105` |
| `camera_freezed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/MediaCapture.kt:97` |
| `camera_missing` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/MediaCapture.kt:133` |
| `camera_opening` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/MediaCapture.kt:101` |
| `camera_switched` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/MediaCapture.kt:192` |
| `capture_already_started` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/MediaCapture.kt:124` |
| `capture_frame` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/FrameNormalizer.kt:56` |
| `capture_frame` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/FrameNormalizer.kt:91` |
| `capture_low_res` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/MediaCapture.kt:143` |
| `capture_released` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/MediaCapture.kt:237` |
| `capture_resume_noop` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/MediaCapture.kt:214` |
| `capture_resume_start` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/MediaCapture.kt:217` |
| `capture_started` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/MediaCapture.kt:168` |
| `capture_stop_failed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/MediaCapture.kt:227` |
| `capturer_started` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/FrameNormalizer.kt:107` |
| `capturer_stopped` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/FrameNormalizer.kt:113` |
| `codec_preferences_failed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1442` |
| `codec_preferences_set` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1440` |
| `copy_encoded_frame_size_mismatch` | Native | `app/src/main/cpp/encoder/vp9_encoder.cpp:539` |
| `data_channel` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1023` |
| `disconnect_cause` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:533` |
| `disconnect_cause` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:558` |
| `disconnect_deferred` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:813` |
| `encode_bad_frame` | Native | `app/src/main/cpp/encoder/vp9_encoder.cpp:573` |
| `encode_bad_rotation` | Native | `app/src/main/cpp/encoder/vp9_encoder.cpp:601` |
| `encode_failed` | Native | `app/src/main/cpp/encoder/vp9_encoder.cpp:778` |
| `encode_no_packet` | Native | `app/src/main/cpp/encoder/vp9_encoder.cpp:824` |
| `encode_odd_size` | Native | `app/src/main/cpp/encoder/vp9_encoder.cpp:581` |
| `encode_raw_img_alloc_failed` | Native | `app/src/main/cpp/encoder/vp9_encoder.cpp:216` |
| `encode_resize_reinit_failed` | Native | `app/src/main/cpp/encoder/vp9_encoder.cpp:637` |
| `encode_rotate_buf_invalid` | Native | `app/src/main/cpp/encoder/vp9_encoder.cpp:671` |
| `encode_vpx_begin` | Native | `app/src/main/cpp/encoder/vp9_encoder.cpp:752` |
| `encode_vpx_begin` | Native | `app/src/main/cpp/encoder/vp9_encoder.cpp:763` |
| `encode_vpx_done` | Native | `app/src/main/cpp/encoder/vp9_encoder.cpp:773` |
| `encoded_frame` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:414` |
| `encoded_frame` | Native | `app/src/main/cpp/encoder/vp9_encoder.cpp:839` |
| `encoded_frame` | Native | `app/src/main/cpp/encoder/vp9_encoder.cpp:846` |
| `encoded_frame_too_large` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:377` |
| `encoded_plane_rejected` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:199` |
| `encoder_cpu_probe` | Native | `app/src/main/cpp/encoder/vp9_encoder.cpp:96` |
| `encoder_created` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoderFactory.kt:61` |
| `encoder_created` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoderFactory.kt:80` |
| `encoder_encode_failed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:233` |
| `encoder_fallback` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:147` |
| `encoder_fallback_switch_signal` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/encoder/FallbackVideoEncoderSelector.kt:95` |
| `encoder_fallback_switch_skipped` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/encoder/FallbackVideoEncoderSelector.kt:88` |
| `encoder_fallback_unavailable` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoderFactory.kt:74` |
| `encoder_init` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:150` |
| `encoder_init` | Native | `app/src/main/cpp/encoder/vp9_encoder.cpp:407` |
| `encoder_init_bad_size` | Native | `app/src/main/cpp/encoder/vp9_encoder.cpp:318` |
| `encoder_init_failed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:122` |
| `encoder_init_failed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:138` |
| `encoder_init_failed` | Native | `app/src/main/cpp/encoder/vp9_encoder.cpp:359` |
| `encoder_init_failed` | Native | `app/src/main/cpp/encoder/vp9_encoder.cpp:379` |
| `encoder_init_rejected` | Native | `app/src/main/cpp/encoder/vp9_encoder.cpp:324` |
| `encoder_init_rejected` | Native | `app/src/main/cpp/encoder/vp9_encoder.cpp:331` |
| `encoder_perf` | Native | `app/src/main/cpp/encoder/vp9_encoder.cpp:906` |
| `encoder_rate_floor` | Native | `app/src/main/cpp/encoder/vp9_encoder.cpp:468` |
| `encoder_rates` | Native | `app/src/main/cpp/encoder/vp9_encoder.cpp:477` |
| `encoder_rates` | Native | `app/src/main/cpp/encoder/vp9_encoder.cpp:486` |
| `encoder_raw_img_alloc` | Native | `app/src/main/cpp/encoder/vp9_encoder.cpp:222` |
| `encoder_reinit` | Native | `app/src/main/cpp/encoder/vp9_encoder.cpp:653` |
| `encoder_released` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:323` |
| `encoder_rotate` | Native | `app/src/main/cpp/encoder/vp9_encoder.cpp:711` |
| `encoder_rotation_mode` | Native | `app/src/main/cpp/encoder/vp9_encoder.cpp:606` |
| `encoder_slow_frame` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:244` |
| `encoder_threads` | Native | `app/src/main/cpp/encoder/vp9_encoder.cpp:394` |
| `engine_init_failed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:208` |
| `engine_init_skipped` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:104` |
| `engine_ready` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:176` |
| `engine_shutdown` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:284` |
| `export_cleanup` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/diag/LogExporter.kt:260` |
| `export_cleanup_failed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/diag/LogExporter.kt:191` |
| `export_failed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/diag/LogExporter.kt:193` |
| `export_zip` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/diag/LogExporter.kt:179` |
| `export_zip` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallScreen.kt:417` |
| `export_zip` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/home/HomeScreen.kt:176` |
| `field_trials_set` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:164` |
| `frame_convert_failed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/FrameNormalizer.kt:77` |
| `hangup` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:623` |
| `ice_candidate_filtered` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:607` |
| `ice_candidate_filtered` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:888` |
| `ice_candidate_local` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:900` |
| `ice_candidate_remote` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:687` |
| `ice_candidate_remote_total trickled=${tallied} sdp=${sdpRemoteCandidateCount} ` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:705` |
| `ice_candidates_removed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1011` |
| `ice_deferred` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:643` |
| `ice_down_ui_suppressed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1226` |
| `ice_down_ui_suppressed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1502` |
| `ice_dropped` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:597` |
| `ice_dropped` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:637` |
| `ice_error_cleared` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1745` |
| `ice_flap_suppressed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1144` |
| `ice_gathering_complete` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:939` |
| `ice_not_connected` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1253` |
| `ice_received` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:598` |
| `ice_receiving` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1007` |
| `ice_regather_failed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1299` |
| `ice_regather_invoked` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1543` |
| `ice_relay_state` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1165` |
| `ice_replayed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1081` |
| `ice_restart_exhausted` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1275` |
| `ice_restart_failed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1289` |
| `ice_restart_requested` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1302` |
| `ice_timeout` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1233` |
| `ice_timeout_restarting` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1225` |
| `ice_turn_error` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:984` |
| `ice_watchdog_ok` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1380` |
| `ice_watchdog_rearmed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1184` |
| `ice_watchdog_rearmed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1359` |
| `ice_watchdog_rearmed reason=relay_candidate skipped=no_watchdog` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1346` |
| `ice_watchdog_stale_tier` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1165` |
| `ice_watchdog_started` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1125` |
| `java_exception` | Native | `app/src/main/cpp/util/jni_util.cpp:86` |
| `jni_binding_missing` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:115` |
| `jni_call` | Native | `app/src/main/cpp/jni/nat_detector_jni.cpp:28` |
| `jni_call` | Native | `app/src/main/cpp/jni/nat_detector_jni.cpp:43` |
| `jni_call` | Native | `app/src/main/cpp/jni/native_log_jni.cpp:35` |
| `jni_call` | Native | `app/src/main/cpp/jni/native_log_jni.cpp:53` |
| `jni_call` | Native | `app/src/main/cpp/jni/native_log_jni.cpp:60` |
| `jni_call` | Native | `app/src/main/cpp/jni/native_log_jni.cpp:65` |
| `jni_call` | Native | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:100` |
| `jni_call` | Native | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:132` |
| `jni_call` | Native | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:240` |
| `jni_call` | Native | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:330` |
| `jni_call` | Native | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:90` |
| `jni_onload` | Native | `app/src/main/cpp/jni/jni_bridge.cpp:105` |
| `jni_return` | Native | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:218` |
| `layer_bps` | Native | `app/src/main/cpp/encoder/vp9_encoder.cpp:441` |
| `local_description_set` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:852` |
| `local_description_set_failed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:858` |
| `log_level_changed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/log/FileLogger.kt:416` |
| `log_sink_degraded` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/WebRtcDemoApp.kt:118` |
| `main_activity_create` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/MainActivity.kt:29` |
| `main_activity_pause` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/MainActivity.kt:49` |
| `main_activity_resume` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/MainActivity.kt:45` |
| `msg_blocked` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:469` |
| `msg_decode_failed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:284` |
| `msg_dropped` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:476` |
| `msg_dropped` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:487` |
| `msg_encode_failed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:482` |
| `msg_received` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:607` |
| `msg_sent` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:492` |
| `nat_done` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeCallbacks.kt:37` |
| `nat_done` | Native | `app/src/main/cpp/nat/nat_detector.cpp:130` |
| `nat_local_address_unknown` | Native | `app/src/main/cpp/nat/nat_detector.cpp:160` |
| `nat_received` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:599` |
| `nat_request` | Native | `app/src/main/cpp/nat/nat_detector.cpp:168` |
| `nat_start` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/nat/NatTypeRepository.kt:75` |
| `nat_start` | Native | `app/src/main/cpp/nat/nat_detector.cpp:141` |
| `nat_start_skipped` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/nat/NatTypeRepository.kt:61` |
| `nat_start_skipped` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/nat/NatTypeRepository.kt:68` |
| `nativeCopyEncodedFrame_rejected` | Native | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:195` |
| `nativeCopyEncodedFrame_rejected` | Native | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:201` |
| `nativeCreate_failed` | Native | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:82` |
| `nativeDetect_rejected` | Native | `app/src/main/cpp/jni/nat_detector_jni.cpp:33` |
| `nativeEncode_rejected` | Native | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:143` |
| `nativeEncode_rejected` | Native | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:151` |
| `nativeEncode_rejected` | Native | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:163` |
| `nativeInit_rejected` | Native | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:110` |
| `nativeSetRates_fold` | Native | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:289` |
| `nativeSetRates_matrix` | Native | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:296` |
| `nativeSetRates_rejected` | Native | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:260` |
| `nativeSetRates_rejected` | Native | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:268` |
| `nativeSetRates_rejected` | Native | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:283` |
| `native_lib_load_failed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeLoader.kt:49` |
| `native_lib_loaded` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeLoader.kt:45` |
| `native_log_flush_failed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeLog.kt:168` |
| `native_log_init` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeLog.kt:123` |
| `native_log_init_failed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeLog.kt:112` |
| `native_log_init_failed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeLog.kt:116` |
| `native_log_init_failed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeLog.kt:137` |
| `native_log_init_skipped` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/WebRtcDemoApp.kt:62` |
| `native_log_set_level_failed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeLog.kt:156` |
| `no_selected_pair` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1582` |
| `offer_create` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:415` |
| `offer_create_failed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:436` |
| `offer_create_rejected` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:407` |
| `offer_created` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:420` |
| `offer_deferred` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:469` |
| `offer_dropped` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:466` |
| `offer_received` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:596` |
| `offer_received` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:457` |
| `offer_replayed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1073` |
| `offer_sent` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:375` |
| `offer_sent` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:430` |
| `offer_set_failed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:441` |
| `on_pause` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallScreen.kt:179` |
| `on_resume` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallScreen.kt:185` |
| `pc_add_track` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/PeerConnectionObserverImpl.kt:125` |
| `pc_close_failed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:839` |
| `pc_closed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:763` |
| `pc_connection_state` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/PeerConnectionObserverImpl.kt:143` |
| `pc_connection_state` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/PeerConnectionObserverImpl.kt:145` |
| `pc_create_failed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:330` |
| `pc_created` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:389` |
| `pc_dispose_failed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:844` |
| `pc_ice_candidate_error` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/PeerConnectionObserverImpl.kt:170` |
| `pc_ice_connection_state` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/PeerConnectionObserverImpl.kt:87` |
| `pc_ice_gathering_state` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/PeerConnectionObserverImpl.kt:96` |
| `pc_local_tracks` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:352` |
| `pc_signaling_state` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/PeerConnectionObserverImpl.kt:82` |
| `pc_start_aborted` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:377` |
| `pc_start_aborted` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:386` |
| `pc_start_rejected` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:283` |
| `pc_starting` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:306` |
| `peer_joined` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:594` |
| `peer_joined` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:886` |
| `peer_left` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:595` |
| `peer_left` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:900` |
| `peer_left action=end_call` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:935` |
| `peer_left action=keep_call` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:912` |
| `peer_nat_received` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/nat/NatTypeRepository.kt:106` |
| `permission_result` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/home/HomeViewModel.kt:103` |
| `pong_miss` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:634` |
| `pong_received` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:606` |
| `preview_recover_armed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallScreen.kt:200` |
| `preview_recover_attempt` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallScreen.kt:221` |
| `preview_recover_give_up` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallScreen.kt:257` |
| `preview_recover_reattached` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallScreen.kt:245` |
| `register_natives_failed` | Native | `app/src/main/cpp/jni/nat_detector_jni.cpp:66` |
| `register_natives_failed` | Native | `app/src/main/cpp/jni/native_log_jni.cpp:90` |
| `register_natives_failed` | Native | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:376` |
| `rejoin_offer_received` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:960` |
| `rejoin_room_full` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1046` |
| `rejoined` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:869` |
| `remote_deferred` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1002` |
| `remote_deferred` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1023` |
| `remote_deferred` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:976` |
| `remote_description_set` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:867` |
| `remote_description_set_failed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:873` |
| `remote_first_frame` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:163` |
| `remote_frame_ignored` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:657` |
| `remote_frame_liveness` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:313` |
| `remote_offer_set_failed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:529` |
| `remote_replay_done` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1642` |
| `remote_replay_done` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1670` |
| `remote_replay_done` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1088` |
| `remote_resolution_changed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:173` |
| `remote_stream_added` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1015` |
| `remote_stream_removed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1019` |
| `remote_track_added` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1032` |
| `renderer_attach_rejected` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:230` |
| `renderer_attach_rejected` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:245` |
| `renderer_attached` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:236` |
| `renderer_attached` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:263` |
| `renderer_created` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:216` |
| `renderer_detach_failed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:285` |
| `renderer_detach_failed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:306` |
| `renderer_detached` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:287` |
| `renderer_detached` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:308` |
| `renderer_on_frame_failed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:256` |
| `renderer_recreate` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:152` |
| `renderer_recreate_skipped` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:149` |
| `renderer_release_failed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:330` |
| `renderer_released` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:334` |
| `renegotiation_needed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1027` |
| `retry_clock_reset` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1436` |
| `retry_clock_started` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1414` |
| `retry_invoked` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:380` |
| `retry_invoked` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:393` |
| `retry_reoffer` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:524` |
| `retry_reoffer` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:536` |
| `retry_reoffer_run` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1618` |
| `room_create_sent` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:454` |
| `room_created` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:587` |
| `room_join_sent` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:461` |
| `room_joined` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:588` |
| `room_not_found action=keep_call` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1062` |
| `room_recreate_invoked` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:693` |
| `room_recreate_invoked` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:699` |
| `room_recreate_invoked` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:706` |
| `room_recreated` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:837` |
| `rtc_config` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:175` |
| `selected_candidate_pair` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/PeerConnectionObserverImpl.kt:154` |
| `server_error` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:600` |
| `server_error` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1051` |
| `server_error` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/home/HomeViewModel.kt:186` |
| `server_error_surfaced` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:547` |
| `session_release` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1247` |
| `session_retry` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:422` |
| `session_start` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/home/HomeViewModel.kt:138` |
| `session_teardown` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1279` |
| `setrates` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:300` |
| `setrates` | Native | `app/src/main/cpp/encoder/vp9_encoder.cpp:438` |
| `setrates_config_set_failed` | Native | `app/src/main/cpp/encoder/vp9_encoder.cpp:495` |
| `setrates_failed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:286` |
| `setrates_rejected` | Native | `app/src/main/cpp/encoder/vp9_encoder.cpp:427` |
| `setrates_rejected` | Native | `app/src/main/cpp/encoder/vp9_encoder.cpp:432` |
| `signaling_lost action=end_call` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:798` |
| `signaling_lost action=keep_call` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:770` |
| `signaling_state` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1003` |
| `signaling_url_rejected` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:84` |
| `state_change` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:768` |
| `stats_failed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1417` |
| `stats_sample` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/StatsMapper.kt:87` |
| `stun_bind_failed` | Native | `app/src/main/cpp/nat/stun_client.cpp:214` |
| `stun_local_address` | Native | `app/src/main/cpp/nat/stun_client.cpp:269` |
| `stun_recvfrom_failed` | Native | `app/src/main/cpp/nat/stun_client.cpp:382` |
| `stun_request` | Native | `app/src/main/cpp/nat/stun_client.cpp:349` |
| `stun_resolve_failed` | Native | `app/src/main/cpp/nat/stun_client.cpp:302` |
| `stun_response` | Native | `app/src/main/cpp/nat/stun_client.cpp:438` |
| `stun_response_error` | Native | `app/src/main/cpp/nat/stun_client.cpp:407` |
| `stun_sendto_failed` | Native | `app/src/main/cpp/nat/stun_client.cpp:359` |
| `stun_socket_failed` | Native | `app/src/main/cpp/nat/stun_client.cpp:196` |
| `stun_socket_ready` | Native | `app/src/main/cpp/nat/stun_client.cpp:230` |
| `surface_changed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:201` |
| `surface_created` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:197` |
| `surface_destroyed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:210` |
| `to_i420_failed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:184` |
| `ts_target_kbps` | Native | `app/src/main/cpp/encoder/vp9_encoder.cpp:412` |
| `ts_target_kbps` | Native | `app/src/main/cpp/encoder/vp9_encoder.cpp:500` |
| `ui_conn_state` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1475` |
| `ui_conn_state_enter` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:461` |
| `uncaught_exception` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/WebRtcDemoApp.kt:82` |
| `video_toggle` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:723` |
| `video_track_missing` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:348` |
| `vp9_not_advertised` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1432` |
| `webrtc_log_throttled` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/LibwebrtcLoggable.kt:89` |
| `ws_close` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:313` |
| `ws_close` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:321` |
| `ws_closing` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:308` |
| `ws_connecting` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:445` |
| `ws_open` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:272` |
| `ws_pong_timeout` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:645` |
| `ws_reconnect_give_up` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:731` |
| `ws_reconnect_scheduled` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:749` |
| `ws_reconnect_skipped` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:723` |
| `ws_reconnect_suppressed` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:523` |
| `ws_rejoin_give_up` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:684` |
| `ws_rejoin_retry` | Kotlin | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:705` |
