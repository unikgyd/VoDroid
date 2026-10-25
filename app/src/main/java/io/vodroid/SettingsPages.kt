package io.vodroid

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.InputType
import android.telecom.TelecomManager
import android.view.*
import android.widget.*
import io.vodroid.ui.*
import net.typeblog.lpac_jni.LocalProfileInfo

/** Secondary destinations share the same spacing, controls and hierarchy. */
internal class SettingsPages(private val host: MainActivity) {
    private val ui get()=host.ui
    private val prefs get()=Engine.prefs
    private fun value(key:String,default:String="")=prefs.getString(key,default)!!
    private fun perform(action:()->Unit)=Engine.task { try{action()}catch(e:Exception){host.notice(e.message ?: "操作失败");throw e} }
    private fun idle():Boolean { if(VoWifiService.current!=null){host.notice("请先断开连接");return false};if(Engine.busy){host.notice("正在处理");return false};return true }
    fun show(route:String){when(route){"cards"->cards();"card-access"->cardAccess();"root-card-access"->cardAccess(true);"root","integration"->root();"tunnel"->tunnel("settings");"tunnel-status"->tunnel("status");"carrier"->carrier();"logs"->logs();"permissions"->permissions();"about"->about();"donate"->donate();else->settings()}}

    private fun settings(){
        val page=host.page("设置")
        val sim=ui.setting(Symbol.SIM,"SIM 与 eSIM",host.profileName()){host.go("cards")};host.addCard(page,sim,20)
        page.addView(ui.section("连接"))
        val group=ui.card(4)
        val network=ui.setting(Symbol.SHIELD,"隧道模式",modeName()){host.go("tunnel")};group.addView(network);group.addView(ui.divider(66))
        group.addView(ui.setting(Symbol.WIFI,"运营商网络"){host.go("carrier")});host.addCard(page,group)
        page.addView(ui.section("应用"))
        val app=ui.card(4);app.addView(ui.setting(Symbol.LOCK,"权限"){host.go("permissions")});app.addView(ui.divider(66));app.addView(ui.setting(Symbol.LOG,"日志"){host.go("logs")});app.addView(ui.divider(66));app.addView(ui.setting(Symbol.INFO,"关于 VoDroid"){host.go("about")});host.addCard(page,app)
        page.addView(ui.section("可选增强"))
        val enhancements=ui.setting(Symbol.ROOT,"Root 与模块",enhancementLabel()){host.go("root")};host.addCard(page,enhancements)
        host.updater{sim.findViewWithTag<TextView>("value").text=host.profileName();network.findViewWithTag<TextView>("value").text=modeName();enhancements.findViewWithTag<TextView>("value").text=enhancementLabel()}
    }
    private fun enhancementLabel()=if(prefs.getBoolean("root_enabled",false))"已启用" else "未启用"
    private fun rootLabel()=if(prefs.getBoolean("root_enabled",false))RootAccess.status.takeUnless{it=="未授权"} ?: "已启用" else RootAccess.status.takeUnless{it=="已授权"} ?: "未授权"
    private fun requestRoot(onSuccess:()->Unit={}){if(!idle())return;perform{if(RootAccess.check()){host.notice("Root 已授权");host.runOnUiThread{onSuccess()}}else{prefs.edit().putBoolean("root_enabled",false).apply();host.notice(RootAccess.status)}}}
    private fun root(){
        val page=host.page("Root 与模块","settings",listOf(ui.iconButton(Symbol.INFO,"模块说明"){moduleInfo()}))
        page.addView(ui.badge("可选增强"),LinearLayout.LayoutParams(-2,-2));page.addView(ui.section("Root 授权"))
        val change:(Boolean)->Unit=change@{enabled->
            if(!idle()){host.go("root");return@change}
            if(enabled)requestRoot() else {perform{Engine.closeCard();RootAccess.disable(host);if(value("card_type","omapi")=="root")prefs.edit().putString("card_type","omapi").apply()}}
        }
        val group=ui.card(4);val control=toggle(Symbol.ROOT,"启用 Root",prefs.getBoolean("root_enabled",false),change);group.addView(control);group.addView(ui.divider(66))
        val state=ui.setting(Symbol.LOCK,"授权状态",rootLabel()){requestRoot()};group.addView(state);group.addView(ui.divider(66))
        val probe=ui.setting(Symbol.SIM,"读卡检测","未检测"){rootProbeSheet()};group.addView(probe);host.addCard(page,group)
        val hidden=ui.text("若使用 Root 隐藏白名单，请先在管理器中授权 VoDroid，再重新打开应用。",13f,Palette.secondary);page.addView(hidden);hidden.visibility=if(RootAccess.status=="su 不可见")View.VISIBLE else View.GONE
        host.addCard(page,ui.setting(Symbol.SIM,"Root 卡槽",if(value("card_type","omapi")=="root")host.accessName() else "未使用"){host.go("root-card-access")})
        integration(page)
        host.updater{val switch=(control as ViewGroup).getChildAt(2) as Switch;val pending=RootAccess.status=="等待授权";switch.isEnabled=!pending&&!Engine.busy;val checked=pending||prefs.getBoolean("root_enabled",false);if(switch.isChecked!=checked){switch.setOnCheckedChangeListener(null);switch.isChecked=checked;switch.setOnCheckedChangeListener{_,v->change(v)}};state.findViewWithTag<TextView>("value").apply{text=rootLabel();setTextColor(if(RootAccess.status=="已授权"&&prefs.getBoolean("root_enabled",false))Palette.success else Palette.secondary)};probe.findViewWithTag<TextView>("value").apply{val report=RootAccess.cardProbe;text=report?.let{"SIM${it.slot+1} · ${if(it.passed)"通过" else "未通过"}"} ?: "未检测";setTextColor(when{report==null->Palette.secondary;report.passed->Palette.success;else->Palette.red})};hidden.visibility=if(RootAccess.status=="su 不可见")View.VISIBLE else View.GONE}
    }
    private fun rootProbeSheet(){host.sheet("读卡检测"){box,dialog->
        var slot=prefs.getInt("slot",0).coerceIn(0,7)
        val count=runCatching{host.getSystemService(android.telephony.TelephonyManager::class.java).activeModemCount}.getOrDefault(2).coerceIn(1,8)
        lateinit var target:LinearLayout
        target=ui.setting(Symbol.SIM,"检测卡槽","SIM${slot+1}"){host.sheet("检测卡槽"){choices,picker->
            (0 until maxOf(count,slot+1)).forEach{candidate->choices.addView(ui.setting(Symbol.SIM,"SIM${candidate+1}",if(candidate==slot)"✓" else ""){slot=candidate;target.findViewWithTag<TextView>("value").text="SIM${slot+1}";picker.dismiss()})}
        }};host.addCard(box,target)
        val port=ui.field("检测端口",prefs.getInt("port",0).toString(),"0");port.second.inputType=InputType.TYPE_CLASS_NUMBER;box.addView(port.first)
        RootAccess.cardProbe?.let{report->box.addView(ui.text("上次 · SIM${report.slot+1} / 端口 ${report.port}",12f,Palette.secondary));box.addView(ui.space(8));box.addView(ui.text(if(report.passed)"只读 APDU · SW %04X · ${report.bytes} 字节".format(report.statusWord) else report.detail.ifBlank{"未通过"},13f,if(report.passed)Palette.success else Palette.secondary));box.addView(ui.space(16))}
        box.addView(ui.action("开始检测",Symbol.CHECK){
            val selectedPort=port.second.text.toString().toIntOrNull();if(selectedPort==null||selectedPort !in 0..7){port.second.error="端口应为 0–7";return@action}
            if(!idle())return@action
            if(!prefs.getBoolean("root_enabled",false)){host.notice("请先启用 Root");return@action}
            val selectedSlot=slot;val aid=value("usim","A0000000871002");dialog.dismiss()
            perform{val report=RootAccess.verifyCard(host,selectedSlot,selectedPort,aid);host.notice("SIM${selectedSlot+1} · ${if(report.passed)"读卡通过" else "读卡未通过"}")}
        })
    }}
    private fun moduleInfo(){host.sheet("模块说明"){box,_->
        box.addView(ui.text("Root · 读卡、系统收件箱\nMagisk / LSPosed · eSIM 兼容、系统桥接",14f,Palette.secondary));box.addView(ui.space(16))
        box.addView(ui.text("在 LSPosed 中选择目标应用，再应用模块配置。安装或升级模块后需重启手机。",13f,Palette.secondary));box.addView(ui.space(12))
        box.addView(ui.text("需要 EID 时，先读取卡片配置。安装调用将转到 VoDroid，确认后下载安装。",13f,Palette.secondary))
    }}
    private fun modeName()=if(prefs.getBoolean("vpn_backend",false))"系统 VPN" else "应用内隧道"
    private fun tunnel(back:String){
        val page=host.page("隧道模式",back);val box=ui.card(4);host.addCard(page,box)
        listOf(false to "应用内隧道",true to "系统 VPN").forEach{(system,label)->
            val row=ui.row().apply{setPadding(ui.dp(8),ui.dp(18),ui.dp(8),ui.dp(18));contentDescription=label}
            val selected=prefs.getBoolean("vpn_backend",false)==system
            row.addView(ui.icon(if(system)Symbol.SHIELD else Symbol.WIFI,23,Palette.accent),LinearLayout.LayoutParams(ui.dp(23),ui.dp(23)).apply{marginEnd=ui.dp(16)})
            val text=ui.vertical();text.addView(ui.text(label,16f,weight=true));text.addView(ui.space(6));text.addView(ui.text(if(system)"由 VoDroid 建立 VPN / TUN" else "可与其他 VPN 同时使用",12f,Palette.secondary));row.addView(text,LinearLayout.LayoutParams(0,-2,1f))
            row.addView(RadioButton(host).apply{isChecked=selected;isClickable=false;isFocusable=false;buttonTintList=ColorStateList.valueOf(Palette.accent)})
            ui.touch(row,if(selected)Palette.soft else Palette.surface,18){prefs.edit().putBoolean("vpn_backend",system).apply();Engine.update();host.go(back);if(VoWifiService.current!=null)host.notice("重连后生效")};box.addView(row)
        }
    }

    private fun cards(){
        val page=host.page("SIM 与 eSIM","settings",listOf(ui.iconButton(Symbol.REFRESH,"刷新配置"){if(idle())perform{Engine.refreshProfiles();rememberProfile()}},ui.iconButton(Symbol.PLUS,"添加 eSIM",Palette.accent,Palette.soft){if(idle())downloadSheet("")}))
        host.addCard(page,ui.setting(if(value("card_type","omapi")=="usb")Symbol.USB else Symbol.SIM,"卡片通道",host.accessName()){host.go("card-access")},16)
        host.addCard(page,ui.setting(Symbol.CHECK,"检测卡片","USIM / eSIM"){if(idle())cardCheck()})
        val progress=ProgressBar(host,null,android.R.attr.progressBarStyleHorizontal).apply{isIndeterminate=true;indeterminateTintList=ColorStateList.valueOf(Palette.accent)};page.addView(progress,LinearLayout.LayoutParams(-1,ui.dp(3)))
        val container=ui.vertical();page.addView(container)
        val eid=ui.setting(Symbol.COPY,"EID"){if(Engine.eid.isNotBlank()){host.copy("EID",Engine.eid);host.notice("已复制")}};page.addView(eid)
        var previous=""
        host.updater{
            progress.visibility=if(Engine.busy)View.VISIBLE else View.INVISIBLE
            eid.visibility=if(Engine.eid.isBlank())View.GONE else View.VISIBLE;eid.findViewWithTag<TextView>("value").text=Engine.eid.takeLast(8)
            val key=Engine.profiles.joinToString{it.iccid+it.state+it.nickName+it.name}+Engine.busy
            if(previous!=key){previous=key;container.removeAllViews()
                if(Engine.profiles.isEmpty()){
                    container.addView(ui.empty(Symbol.SIM,if(Engine.busy)"正在读取" else "尚未读取配置"))
                    if(!Engine.busy)container.addView(ui.action("读取配置",Symbol.REFRESH,Palette.soft,Palette.accent){if(idle())perform{Engine.refreshProfiles();rememberProfile()}},LinearLayout.LayoutParams(-1,ui.dp(52)).apply{bottomMargin=ui.dp(24)})
                }else{
                    container.addView(ui.section("配置"))
                    Engine.profiles.forEach{profile->val active=profile.state==LocalProfileInfo.State.Enabled
                        val card=ui.row().apply{setPadding(ui.dp(18),ui.dp(18),ui.dp(8),ui.dp(18));minimumHeight=ui.dp(90)}
                        card.addView(ui.icon(Symbol.SIM,28,if(active)Palette.accent else Palette.secondary),LinearLayout.LayoutParams(ui.dp(28),ui.dp(28)).apply{marginEnd=ui.dp(16)})
                        val text=ui.vertical();val heading=ui.row();heading.addView(ui.text(profileTitle(profile),17f,weight=true),LinearLayout.LayoutParams(0,-2,1f));if(active)heading.addView(ui.badge("使用中",Palette.success,0xFFE6F4EA.toInt(),true));text.addView(heading);text.addView(ui.space(7));text.addView(ui.text(listOf(profile.providerName,profile.iccid.takeLast(4)).filter{it.isNotBlank()}.joinToString(" · "),12f,Palette.secondary));card.addView(text,LinearLayout.LayoutParams(0,-2,1f));card.addView(ui.iconButton(Symbol.MORE,"配置选项"){profileMenu(profile)})
                        ui.touch(card,if(active)Palette.soft else Palette.surface,22){if(active)profileMenu(profile) else if(idle())host.confirm("切换到 ${profileTitle(profile)}？"){perform{Engine.profileAction(profile,"enable");rememberProfile()}}};host.addCard(container,card)
                    }
                }
            }
        }
    }
    private fun profileTitle(p:LocalProfileInfo)=p.nickName.ifBlank{p.name.ifBlank{p.providerName.ifBlank{"eSIM"}}}
    private fun cardCheck(){host.sheet("卡片检测"){box,dialog->
        val progress=ProgressBar(host).apply{indeterminateTintList=ColorStateList.valueOf(Palette.accent)};box.addView(progress,LinearLayout.LayoutParams(ui.dp(28),ui.dp(28)).apply{gravity=Gravity.CENTER_HORIZONTAL;topMargin=ui.dp(20);bottomMargin=ui.dp(20)})
        fun result(label:String,ok:Boolean,detail:String){Engine.log("$label · ${if(ok)"成功" else "失败"}：$detail");host.runOnUiThread{if(dialog.isShowing){val row=ui.setting(if(ok)Symbol.CHECK else Symbol.ALERT,label,if(ok)"可用" else "不可用"){host.sheet(label){content,_->content.addView(ui.text(detail,14f,Palette.secondary))}};row.findViewWithTag<TextView>("value").setTextColor(if(ok)Palette.success else Palette.red);box.addView(row)}}}
        Engine.task{
            try {
                Engine.closeCard();val card=Engine.connectCard();result("卡片通道",true,host.accessName())
                try{io.vodroid.core.SimCard(card).use{sim->sim.connect(value("usim","A0000000871002"));val id=sim.identity(prefs.getInt("mnc_length",0));result("USIM / APDU",true,"身份读取成功 · ${id.mcc()} / ${id.mnc()}")}}catch(e:Exception){result("USIM / APDU",false,e.message ?: e.javaClass.simpleName)}
                try{Engine.refreshProfiles();result("eSIM 管理",true,"${Engine.profiles.size} 个配置")}catch(e:Exception){result("eSIM 管理",false,e.message ?: e.javaClass.simpleName)}
            }catch(e:Exception){result("卡片通道",false,e.message ?: e.javaClass.simpleName)}finally{host.runOnUiThread{progress.visibility=View.GONE}}
        }
    }}
    private fun rememberProfile(){prefs.edit().putString("profile_label",Engine.profiles.firstOrNull{it.state==LocalProfileInfo.State.Enabled}?.let{profileTitle(it)} ?: "").apply()}
    private fun profileMenu(profile:LocalProfileInfo){host.sheet(profileTitle(profile)){box,dialog->
        box.addView(ui.mono(profile.iccid,13f));box.addView(ui.space(14))
        box.addView(ui.setting(Symbol.EDIT,"重命名"){dialog.dismiss();if(idle())host.sheet("重命名"){content,editDialog->val field=ui.field("名称",profileTitle(profile));content.addView(field.first);content.addView(ui.action("保存",Symbol.CHECK){val text=field.second.text.toString().trim();if(text.isNotEmpty()){editDialog.dismiss();perform{Engine.profileAction(profile,"rename",text);rememberProfile()}}})}})
        box.addView(ui.setting(Symbol.POWER,if(profile.state==LocalProfileInfo.State.Enabled)"停用" else "启用"){dialog.dismiss();if(idle())host.confirm(if(profile.state==LocalProfileInfo.State.Enabled)"停用此配置？" else "启用此配置？"){perform{Engine.profileAction(profile,if(profile.state==LocalProfileInfo.State.Enabled)"disable" else "enable");rememberProfile()}}})
        box.addView(ui.setting(Symbol.TRASH,"删除配置"){dialog.dismiss();if(idle())host.confirm("永久删除此 eSIM 配置？"){perform{Engine.profileAction(profile,"delete");rememberProfile()}}})
    }}
    fun downloadSheet(code:String){host.startActivity(EsimHandoff.create(host,code,false,null,null))}

    private fun cardAccess(rootOnly:Boolean=false){
        val page=host.page(if(rootOnly)"Root 卡槽" else "卡片通道",if(rootOnly)"root" else "cards")
        var type=value("card_type","omapi")
        if(rootOnly)type="root"
        val selected=ui.card(4);page.addView(selected)
        if(!rootOnly&&type=="root")host.addCard(page,ui.setting(Symbol.ROOT,"当前使用 Root 卡槽","可选增强"){host.go("root-card-access")})
        page.addView(ui.space(16));val details=ui.card(20);host.addCard(page,details)
        var reader=value("reader","SIM1")
        val slot=ui.field("卡槽",prefs.getInt("slot",0).toString(),"0");slot.second.inputType=InputType.TYPE_CLASS_NUMBER
        val port=ui.field("端口",prefs.getInt("port",0).toString(),"0");port.second.inputType=InputType.TYPE_CLASS_NUMBER
        fun fields(){details.removeAllViews();details.visibility=if(!rootOnly&&type=="root")View.GONE else View.VISIBLE;when(type){"usb"->{details.addView(ui.setting(Symbol.USB,"USB 读卡器",if(value("usb").isBlank())"未选择" else "已选择"){host.selectUsb()})};"omapi"->details.addView(ui.setting(Symbol.SIM,"手机卡槽",reader){readerSheet(reader){reader=it;fields()}});else->if(rootOnly){details.addView(slot.first);details.addView(port.first)}}}
        fun choices(){selected.removeAllViews();selected.visibility=if(rootOnly)View.GONE else View.VISIBLE;listOf(Triple("omapi","OMAPI",Symbol.SIM),Triple("usb","USB CCID",Symbol.USB)).forEach{(id,label,icon)->val row=ui.row().apply{setPadding(ui.dp(16),ui.dp(12),ui.dp(12),ui.dp(12));minimumHeight=ui.dp(60);contentDescription=label};row.addView(ui.icon(icon,22,Palette.accent),LinearLayout.LayoutParams(ui.dp(22),ui.dp(22)).apply{marginEnd=ui.dp(16)});row.addView(ui.text(label,15f),LinearLayout.LayoutParams(0,-2,1f));row.addView(RadioButton(host).apply{isChecked=type==id;isClickable=false;isFocusable=false;buttonTintList=ColorStateList.valueOf(Palette.accent)});ui.touch(row){type=id;choices();fields()};selected.addView(row)}}
        choices();fields()
        val advanced=ui.card(20);val usim=ui.field("USIM AID",value("usim","A0000000871002"));val isdr=ui.field("ISD-R AID",value("isdr","A0000005591010FFFFFFFF8900000100"));advanced.addView(usim.first);advanced.addView(isdr.first);advanced.visibility=View.GONE
        host.addCard(page,ui.setting(Symbol.SETTINGS,"高级"){advanced.visibility=if(advanced.visibility==View.GONE)View.VISIBLE else View.GONE});host.addCard(page,advanced)
        page.addView(ui.action("保存",Symbol.CHECK){
            if(!idle())return@action
            if(!rootOnly&&type=="root"){host.notice("请选择 OMAPI 或 USB 通道");return@action}
            for(edit in listOf(usim.second,isdr.second)){if(!edit.text.toString().matches(Regex("(?i)([0-9a-f]{2}){5,16}"))){advanced.visibility=View.VISIBLE;edit.error="AID 格式无效";return@action}}
            val s=slot.second.text.toString().toIntOrNull();val p=port.second.text.toString().toIntOrNull();if(type=="root"&&(s==null||p==null||s !in 0..7||p !in 0..7)){host.notice("卡槽和端口应为 0–7");return@action}
            if(type=="root"&&!prefs.getBoolean("root_enabled",false)){requestRoot();return@action}
            prefs.edit().putString("card_type",type).putString("reader",reader).putInt("slot",s ?: 0).putInt("port",p ?: 0).putString("usim",usim.second.text.toString().uppercase()).putString("isdr",isdr.second.text.toString().uppercase()).remove("profile_label").apply()
            perform{Engine.closeCard();RootAccess.disconnect()};host.go(if(rootOnly)"root" else "cards");host.notice("已保存")
        })
    }
    private fun readerSheet(selected:String,onSelect:(String)->Unit){host.sheet("手机卡槽"){box,dialog->
        val list=ui.vertical();box.addView(list);val progress=ProgressBar(host).apply{indeterminateTintList=ColorStateList.valueOf(Palette.accent)};list.addView(progress,LinearLayout.LayoutParams(ui.dp(28),ui.dp(28)).apply{gravity=Gravity.CENTER_HORIZONTAL;topMargin=ui.dp(24);bottomMargin=ui.dp(24)})
        box.addView(ui.setting(Symbol.EDIT,"手动输入",selected){dialog.dismiss();host.sheet("OMAPI 读卡器"){content,editDialog->val field=ui.field("名称",selected,"SIM1");content.addView(field.first);content.addView(ui.action("选择",Symbol.CHECK){val name=field.second.text.toString().trim();if(name.isBlank()){field.second.error="请输入名称"}else{onSelect(name);editDialog.dismiss()}})}})
        Engine.task{val found=runCatching{CardAccess.simReaders(host)};host.runOnUiThread{if(dialog.isShowing){list.removeAllViews();val readers=found.getOrDefault(emptyList());if(readers.isEmpty())list.addView(ui.empty(Symbol.SIM,"未发现 SIM 读卡器")) else readers.forEach{reader->val row=ui.setting(Symbol.SIM,reader.name,if(reader.present)"已插卡" else "无卡"){if(reader.present){onSelect(reader.name);dialog.dismiss()}else host.notice("请先插入卡片")};row.findViewWithTag<TextView>("value").setTextColor(if(reader.present)Palette.success else Palette.muted);list.addView(row)}}}}
    }}
    private fun carrier(){
        val page=host.page("运营商网络","settings")
        val form=ui.card(20);val epdg=ui.field("ePDG",value("epdg"),"自动发现 · 多个用逗号分隔");val pcscf=ui.field("P-CSCF",value("pcscf"),"自动获取");val smsc=ui.field("SMSC",value("smsc"),"运营商 SMSC");listOf(epdg,pcscf,smsc).forEach{form.addView(it.first)};host.addCard(page,form)
        val policies=listOf("auto" to "自动", "modern" to "Modern", "legacy" to "Legacy")
        lateinit var ike:LinearLayout
        fun policyName()=policies.firstOrNull{it.first==value("ike_policy").ifBlank{"auto"}}?.second ?: "自动"
        ike=ui.setting(Symbol.SHIELD,"IKE 策略",policyName()){host.sheet("IKE 策略"){box,dialog->
            policies.forEach{(id,label)->box.addView(ui.setting(Symbol.SHIELD,label,if(value("ike_policy").ifBlank{"auto"}==id)"✓" else ""){
                prefs.edit().putString("ike_policy",id).apply();ike.findViewWithTag<TextView>("value").text=label;dialog.dismiss()
            })}
            box.addView(ui.space(12));box.addView(ui.text("自动：运营商配置与有限回退\nModern：MODP14，优先 SHA-256\nLegacy：MODP2，优先 SHA-1",12f,Palette.secondary))
        }};host.addCard(page,ike)
        val methods=listOf("auto" to "自动", "aka" to "AKA", "aka-prime" to "AKA′")
        lateinit var eap:LinearLayout
        eap=ui.setting(Symbol.LOCK,"SIM 认证",methods.firstOrNull{it.first==value("eap_policy","auto")}?.second ?: "自动"){host.sheet("SIM 认证"){box,dialog->
            methods.forEach{(id,label)->box.addView(ui.setting(Symbol.LOCK,label,if(value("eap_policy","auto")==id)"✓" else ""){
                prefs.edit().putString("eap_policy",id).apply();eap.findViewWithTag<TextView>("value").text=label;dialog.dismiss();if(VoWifiService.current!=null)host.notice("重连后生效")
            })}
        }};host.addCard(page,eap)
        lateinit var mnc:LinearLayout;mnc=ui.setting(Symbol.SIM,"MNC 位数",when(prefs.getInt("mnc_length",0)){2->"2";3->"3";else->"自动"}){host.sheet("MNC 位数"){box,dialog->listOf(0 to "自动",2 to "2",3 to "3").forEach{(id,label)->box.addView(ui.setting(Symbol.SIM,label,if(prefs.getInt("mnc_length",0)==id)"✓" else ""){prefs.edit().putInt("mnc_length",id).apply();mnc.findViewWithTag<TextView>("value").text=label;dialog.dismiss()})}}};host.addCard(page,mnc)
        val advanced=ui.card(4);advanced.addView(toggle(Symbol.SHIELD,"强制 IMS IPsec",prefs.getBoolean("ims_ipsec",false)){prefs.edit().putBoolean("ims_ipsec",it).apply()});advanced.addView(ui.divider(66));advanced.addView(toggle(Symbol.WIFI,"SIP over TCP",prefs.getBoolean("sip_tcp",false)){prefs.edit().putBoolean("sip_tcp",it).apply()});host.addCard(page,advanced,24)
        page.addView(ui.action("保存",Symbol.CHECK){prefs.edit().putString("epdg",epdg.second.text.toString().trim()).putString("pcscf",pcscf.second.text.toString().trim()).putString("smsc",smsc.second.text.toString().trim()).apply();host.notice(if(VoWifiService.current!=null)"已保存，重连后生效" else "已保存");host.go("settings")})
    }

    private fun integration(page:LinearLayout){
        page.addView(ui.section("系统集成"))
        val basic=ui.card(4);basic.addView(toggle(Symbol.PHONE,"系统通话",prefs.getBoolean("system_calls",false)){prefs.edit().putBoolean("system_calls",it).apply();if(it)perform{SystemIntegration.register(host)}});basic.addView(ui.divider(66));basic.addView(toggle(Symbol.MESSAGE,"系统短信",prefs.getBoolean("system_sms",false)){enabled->prefs.edit().putBoolean("system_sms",enabled).apply();if(enabled){if(prefs.getBoolean("root_enabled",false))SystemIntegration.syncInbox(host) else requestRoot{SystemIntegration.syncInbox(host)}}});host.addCard(page,basic)
        host.addCard(page,ui.setting(Symbol.MESSAGE,"系统收件箱","重试同步"){SystemIntegration.syncInbox(host);host.notice("正在同步")})
        host.addCard(page,ui.setting(Symbol.PHONE,"通话账户"){host.startActivity(Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS))})
        page.addView(ui.section("模块功能"))
        val root=ui.card(4);listOf(Triple("esim_app_compat","应用 eSIM 兼容",Symbol.SIM),Triple("system_lpa","系统 LPA",Symbol.CHIP),Triple("carrier_access","运营商卡访问",Symbol.LOCK),Triple("system_sms_send","系统短信发送",Symbol.MESSAGE)).forEachIndexed{i,(key,label,icon)->if(i>0)root.addView(ui.divider(66));root.addView(toggle(icon,label,prefs.getBoolean(key,false)){prefs.edit().putBoolean(key,it).apply()})};host.addCard(page,root)
        page.addView(ui.action("应用模块配置",Symbol.CHIP){if(idle())perform{if(prefs.getBoolean("system_lpa",false)){check(Engine.eid.isNotBlank()){ "请先读取 eSIM 配置" }};RootAccess.configure(host,prefs.getBoolean("system_lpa",false),prefs.getBoolean("carrier_access",false),prefs.getBoolean("system_sms_send",false));host.notice("已应用，请重启目标应用")}})
    }
    private fun toggle(icon:Symbol,label:String,checked:Boolean,onChange:(Boolean)->Unit):View{
        val row=ui.row().apply{minimumHeight=ui.dp(70);setPadding(ui.dp(18),ui.dp(10),ui.dp(12),ui.dp(10))};row.addView(ui.icon(icon,22,Palette.accent),LinearLayout.LayoutParams(ui.dp(22),ui.dp(22)).apply{marginEnd=ui.dp(18)});row.addView(ui.text(label,15f),LinearLayout.LayoutParams(0,-2,1f))
        val control=Switch(host).apply{isChecked=checked;contentDescription=label;showText=false;thumbTintList=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(Palette.accent,Palette.secondary));trackTintList=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(Palette.mint,Palette.line));setOnCheckedChangeListener{_,value->onChange(value)}};row.addView(control);ui.touch(row){control.isChecked=!control.isChecked};return row
    }
    private fun permissions(){
        val page=host.page("权限","settings");val group=ui.card(4);host.addCard(page,group)
        val values=mutableListOf<Pair<String,TextView>>()
        listOf(Triple("麦克风",Manifest.permission.RECORD_AUDIO,Symbol.MUTE),Triple("通知",Manifest.permission.POST_NOTIFICATIONS,Symbol.MESSAGE),Triple("电话",Manifest.permission.READ_PHONE_STATE,Symbol.PHONE)).forEachIndexed{i,(label,permission,icon)->if(i>0)group.addView(ui.divider(66));val row=ui.setting(icon,label){host.requestPermissions(arrayOf(permission),32)};group.addView(row);values.add(permission to row.findViewWithTag("value"))}
        host.addCard(page,ui.setting(Symbol.EXTERNAL,"应用设置"){host.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:${host.packageName}")))})
        host.addCard(page,ui.setting(Symbol.MESSAGE,"短信与来电提醒"){EventNotifications.prepare(host);host.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,host.packageName))})
        if(android.os.Build.VERSION.SDK_INT>=34)host.addCard(page,ui.setting(Symbol.PHONE,"锁屏来电"){host.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,android.net.Uri.parse("package:${host.packageName}")))})
        host.updater{values.forEach{(permission,text)->val granted=host.checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED;text.text=if(granted)"已允许" else "未允许";text.setTextColor(if(granted)Palette.success else Palette.muted)}}
    }
    private fun logs(){
        var errorsOnly=false
        val page=host.page("日志","settings",listOf(ui.iconButton(Symbol.COPY,"复制日志"){host.copy("VoDroid 日志",synchronized(Engine){Engine.logs.joinToString("\n")});host.notice("已复制")},ui.iconButton(Symbol.SHARE,"分享日志"){host.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,synchronized(Engine){Engine.logs.joinToString("\n")}),"分享日志"))},ui.iconButton(Symbol.TRASH,"清空日志"){host.confirm("清空日志？"){synchronized(Engine){Engine.logs.clear()};Engine.update()}}),scroll=false)
        val filters=ui.row();val all=ui.action("全部",fill=Palette.soft,color=Palette.accent){};val errors=ui.action("异常",fill=Color.TRANSPARENT,color=Palette.secondary){};filters.addView(all,LinearLayout.LayoutParams(0,ui.dp(42),1f).apply{marginEnd=ui.dp(8)});filters.addView(errors,LinearLayout.LayoutParams(0,ui.dp(42),1f));page.addView(filters);page.addView(ui.space(16))
        val text=ui.mono("",12f).apply{setTextIsSelectable(true);setLineSpacing(ui.dp(7).toFloat(),1f)};val scroll=ScrollView(host).apply{isVerticalScrollBarEnabled=false;addView(text)};page.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        var signature=""
        fun refresh(){val snapshot=synchronized(Engine){Engine.logs.toList()}.filter{!errorsOnly||it.contains(Regex("失败|错误|异常|断开|拒绝|[Ee]rror|[Ee]xception"))};val content=snapshot.joinToString("\n").ifBlank{if(errorsOnly)"暂无异常" else "暂无日志"};if(content==signature)return;signature=content;val bottom=text.height-scroll.scrollY-scroll.height<ui.dp(80);text.text=content;if(bottom)scroll.post{scroll.fullScroll(View.FOCUS_DOWN)}}
        fun filter(errorsSelected:Boolean){errorsOnly=errorsSelected;ui.touch(all,if(!errorsOnly)Palette.soft else Color.TRANSPARENT){filter(false)};ui.touch(errors,if(errorsOnly)Palette.soft else Color.TRANSPARENT){filter(true)};(all.getChildAt(0) as TextView).setTextColor(if(!errorsOnly)Palette.accent else Palette.secondary);(errors.getChildAt(0) as TextView).setTextColor(if(errorsOnly)Palette.accent else Palette.secondary);refresh()}
        filter(false);host.updater{refresh()}
    }
    fun connectionDetails(){host.sheet("连接详情"){box,_->
        val health=VoWifiService.current?.health()
        listOf("状态" to Engine.phase,"SIM" to host.profileName(),"卡片" to host.accessName(),"隧道" to modeName(),"认证" to (health?.eap() ?: "—"),"加密" to (health?.suite() ?: "—"),"IKE 分片" to (if(!Engine.tunnelReady)"—" else if(health?.fragmentation()==true)"已协商" else "对端未启用"),"SA 换钥" to (health?.let{if(it.rekeys()==0)"等待周期" else "已完成 ${it.rekeys()} 次"} ?: "—"),"ePDG" to Engine.epdg.ifBlank{"—"},"P-CSCF" to Engine.pcscf.ifBlank{"—"}).forEach{(label,value)->val row=ui.row().apply{setPadding(0,ui.dp(12),0,ui.dp(12))};row.addView(ui.text(label,13f,Palette.secondary),LinearLayout.LayoutParams(ui.dp(74),-2));row.addView(ui.text(value,14f).apply{setTextIsSelectable(true)},LinearLayout.LayoutParams(0,-2,1f));box.addView(row)}
    }}
    private fun about(){
        val version=host.packageManager.getPackageInfo(host.packageName,PackageManager.PackageInfoFlags.of(0)).versionName
        val page=host.page("关于 VoDroid","settings");val top=ui.card(26);top.gravity=Gravity.CENTER;top.addView(ui.icon(Symbol.WIFI,48,Palette.accent));top.addView(ui.space(20));top.addView(ui.text("VoDroid",28f));top.addView(ui.space(8));top.addView(ui.text("$version · Android 13+",13f,Palette.secondary));host.addCard(page,top,20)
        val project=ui.card(4);project.addView(ui.setting(Symbol.PERSON,"作者",ProjectInfo.AUTHOR){openUrl(ProjectInfo.AUTHOR_URL)});project.addView(ui.divider(66));project.addView(ui.setting(Symbol.EXTERNAL,"GitHub 仓库","VoDroid"){openUrl(ProjectInfo.REPOSITORY_URL)});project.addView(ui.divider(66));project.addView(ui.setting(Symbol.MESSAGE,"问题反馈","GitHub Issues"){openUrl(ProjectInfo.ISSUES_URL)});host.addCard(page,project)
        host.addCard(page,ui.setting(Symbol.HEART,"支持项目","USDT"){host.go("donate")})
        host.addCard(page,ui.setting(Symbol.INFO,"开源许可","GPL-3.0"){host.sheet("开源许可"){box,_->box.addView(ui.text("VoDroid · GPL-3.0\nOpenEUICC / libeuicc · GPL-3.0 / LGPL-2.1",14f,Palette.secondary));box.addView(ui.space(16));box.addView(ui.text("VoCat 仅作流程参考，未包含其源代码。完整许可随源代码和安装包分发。",12f,Palette.secondary))}})
    }
    private fun donate(){
        val page=host.page("支持项目","about")
        page.addView(ui.text("感谢你支持 VoDroid 持续开发。",14f,Palette.secondary));page.addView(ui.space(20))
        DonationInfo.destinations.forEach{destination->
            val card=ui.card(20);val heading=ui.row();val labels=ui.vertical();labels.addView(ui.text("USDT · ${destination.chain}",15f,weight=true));labels.addView(ui.space(5));labels.addView(ui.text(destination.network,12f,Palette.secondary));heading.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
            heading.addView(ui.iconButton(Symbol.COPY,"复制 ${destination.chain} 地址",Palette.accent){host.copy("USDT · ${destination.chain} / ${destination.network}",destination.address);host.notice("地址已复制")});card.addView(heading);card.addView(ui.space(12))
            card.addView(ui.mono(destination.address,13f,Palette.ink).apply{tag="donation-address-${destination.id}";textDirection=View.TEXT_DIRECTION_LTR;setTextIsSelectable(true)},LinearLayout.LayoutParams(-1,-2));host.addCard(page,card)
        }
        host.addCard(page,ui.setting(Symbol.EXTERNAL,"捐赠信息来源","VoWin"){openUrl(DonationInfo.SOURCE_URL)})
    }
    private fun openUrl(url:String){runCatching{host.startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url)))}.onFailure{host.notice("未找到浏览器")}}
}
