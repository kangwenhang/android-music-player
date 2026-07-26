package com.captiva.musicplayer;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 内置简易软键盘
 * 适配车机输入法不可用的情况
 * 支持中英文切换、数字、常用符号
 * 通过回调通知输入内容变化
 */
public class SimpleKeyboardView extends LinearLayout {

    public interface OnTextChangedListener {
        void onTextChanged(String text);
    }

    private OnTextChangedListener listener;
    private TextView tvInput;
    private final StringBuilder text = new StringBuilder();
    private boolean chineseMode = false;

    // 中文拼音映射(简易)
    private final String[][] pinyinMap = {
        {"a", "啊阿呀"},
        {"ai", "爱艾哀"},
        {"an", "安按暗案"},
        {"ang", "昂"},
        {"ao", "奥傲澳"},
        {"ba", "把八吧爸"},
        {"bai", "白百拜败"},
        {"ban", "办半班般搬版"},
        {"bang", "帮棒膀绑榜"},
        {"bao", "报保包宝抱暴薄"},
        {"bei", "被北倍备背杯辈悲"},
        {"ben", "本苯奔"},
        {"beng", "蹦崩绷"},
        {"bi", "比必笔币避鼻彼壁闭"},
        {"bian", "边变便遍辨编鞭"},
        {"biao", "表标彪"},
        {"bie", "别瘪"},
        {"bin", "宾滨彬"},
        {"bing", "并病兵冰丙"},
        {"bo", "播波薄博拨勃驳"},
        {"bu", "不步部补布捕"},
        {"ca", "擦"},
        {"cai", "才采菜裁财踩彩猜"},
        {"can", "参残餐惨灿"},
        {"cang", "仓藏苍舱"},
        {"cao", "草操曹"},
        {"ce", "测侧策册"},
        {"ceng", "层曾蹭"},
        {"cha", "查茶插差察叉"},
        {"chai", "拆柴差"},
        {"chan", "产禅缠颤"},
        {"chang", "长唱常场厂偿畅尝"},
        {"chao", "超朝吵抄潮巢"},
        {"che", "车彻撤"},
        {"chen", "陈沉晨趁臣"},
        {"cheng", "成程城承称秤诚惩"},
        {"chi", "吃迟池持迟尺赤齿"},
        {"chong", "充冲虫崇宠"},
        {"chou", "抽愁丑臭酬"},
        {"chu", "出处除初楚触储厨"},
        {"chuan", "传船穿串喘"},
        {"chuang", "创窗床闯疮"},
        {"chui", "吹垂锤炊"},
        {"chun", "春纯蠢唇"},
        {"chuo", "戳辍"},
        {"ci", "此次词刺慈磁瓷"},
        {"cong", "从聪匆葱"},
        {"cou", "凑"},
        {"cu", "促粗醋簇"},
        {"cuan", "窜篡蹿"},
        {"cui", "催翠脆摧崔"},
        {"cun", "村存寸"},
        {"cuo", "措错搓挫"},
        {"da", "大打达搭答"},
        {"dai", "代带待戴袋逮怠"},
        {"dan", "单但担弹胆旦诞蛋淡"},
        {"dang", "当党挡档荡"},
        {"dao", "到道倒岛导刀盗稻蹈"},
        {"de", "的得德"},
        {"dei", "得"},
        {"deng", "等灯登蹬凳"},
        {"di", "第低地底弟递滴帝敌堤"},
        {"dian", "点电店典殿淀颠垫"},
        {"diao", "调掉钓雕吊"},
        {"die", "跌叠蝶爹"},
        {"ding", "定顶丁订钉叮"},
        {"diu", "丢"},
        {"dong", "动东洞冬懂栋"},
        {"dou", "都斗豆抖兜陡"},
        {"du", "度独读毒督堵赌杜"},
        {"duan", "段短断端锻"},
        {"dui", "对队堆兑"},
        {"dun", "顿吨蹲盾钝"},
        {"duo", "多朵夺躲堕剁"},
        {"e", "饿额恶俄鹅"},
        {"en", "恩"},
        {"er", "而二耳儿尔"},
        {"fa", "发法罚乏伐"},
        {"fan", "反烦翻饭犯泛番帆贩"},
        {"fang", "方放房防访仿纺妨"},
        {"fei", "非飞费废肥匪沸"},
        {"fen", "分纷粉份奋愤芬"},
        {"feng", "风丰封峰逢锋蜂缝凤"},
        {"fo", "佛"},
        {"fou", "否"},
        {"fu", "服父府副负复福富符腐附夫肤浮符"},
        {"ga", "噶嘎"},
        {"gai", "该改概盖溉"},
        {"gan", "干感敢甘赶杆肝柑"},
        {"gang", "刚钢岗港纲冈"},
        {"gao", "高告搞稿糕膏"},
        {"ge", "个歌哥各革隔阁格鸽戈"},
        {"gei", "给"},
        {"gen", "跟根"},
        {"geng", "更耕"},
        {"gong", "公共工功供宫弓贡攻共"},
        {"gou", "够购勾沟狗钩构"},
        {"gu", "古故顾骨估鼓姑股固"},
        {"gua", "挂刮瓜寡"},
        {"guai", "怪乖拐"},
        {"guan", "关观管馆官贯冠灌"},
        {"guang", "光广逛"},
        {"gui", "规贵鬼桂归柜硅"},
        {"gun", "滚棍"},
        {"guo", "国过果锅裹郭"},
        {"ha", "哈蛤"},
        {"hai", "还孩海害骇"},
        {"han", "汉寒韩含喊函汗"},
        {"hang", "航行航"},
        {"hao", "好号毫豪耗浩"},
        {"he", "和河喝合何荷核贺赫"},
        {"hei", "黑嘿"},
        {"hen", "很恨狠痕"},
        {"heng", "横哼恒"},
        {"hong", "红洪宏轰弘"},
        {"hou", "后候厚侯喉吼"},
        {"hu", "户湖呼护互胡壶虎糊弧"},
        {"hua", "话花化华画划滑哗"},
        {"huai", "坏怀槐"},
        {"huan", "还换环缓欢幻患焕唤"},
        {"huang", "黄慌皇荒晃簧凰"},
        {"hui", "会回灰汇辉挥辉惠毁悔慧"},
        {"hun", "婚混魂浑昏"},
        {"huo", "活火货或获祸豁霍"},
        {"ji", "机及级即记几技己计集积击基际激纪鸡剂迹"},
        {"jia", "家加价假甲架驾嘉夹"},
        {"jian", "见间建件减检简尖坚键渐健肩剑"},
        {"jiang", "江将讲奖降姜匠桨蒋"},
        {"jiao", "教交叫较脚角胶搅骄郊缴"},
        {"jie", "接结节街介借姐届界截解揭戒"},
        {"jin", "进金近紧今禁仅尽劲斤晋"},
        {"jing", "经精京景静惊竞敬井警境"},
        {"jiong", "窘炯"},
        {"jiu", "就九久旧酒救纠舅韭"},
        {"ju", "据局举具句聚剧巨距拒俱"},
        {"juan", "卷捐倦圈绢"},
        {"jue", "决觉绝爵掘"},
        {"jun", "军均君菌俊"},
        {"ka", "卡咖喀"},
        {"kai", "开凯揩"},
        {"kan", "看砍堪刊勘"},
        {"kang", "抗康慷慷"},
        {"kao", "考靠烤拷"},
        {"ke", "可课客科克刻颗棵壳咳"},
        {"ken", "肯啃恳"},
        {"keng", "坑"},
        {"kong", "空控孔恐"},
        {"kou", "口扣寇"},
        {"ku", "苦哭裤库枯酷"},
        {"kua", "跨夸垮挎"},
        {"kuai", "快块筷"},
        {"kuan", "宽款"},
        {"kuang", "狂况矿框旷"},
        {"kui", "亏愧溃馈"},
        {"kun", "困昆捆坤"},
        {"kuo", "扩括阔廓"},
        {"la", "拉啦辣腊蜡垃"},
        {"lai", "来赖"},
        {"lan", "蓝烂览懒拦栏兰澜"},
        {"lang", "浪狼朗郎廊"},
        {"lao", "老劳牢捞佬落"},
        {"le", "了乐勒"},
        {"lei", "类累雷泪垒"},
        {"leng", "冷愣"},
        {"li", "里理力立李利历例离粒礼丽"},
        {"lia", "俩"},
        {"lian", "连联恋练廉脸莲链怜"},
        {"liang", "两亮量凉粮梁辆良谅"},
        {"liao", "了料聊辽疗撩"},
        {"lie", "列烈猎裂劣咧"},
        {"lin", "林临邻磷淋麟"},
        {"ling", "领令另灵零龄凌铃陵"},
        {"liu", "六留刘流柳溜"},
        {"long", "龙隆笼弄聋"},
        {"lou", "楼搂篓漏陋"},
        {"lu", "路陆录露鲁炉鹿"},
        {"lv", "绿律旅率铝滤"},
        {"luan", "乱卵峦"},
        {"lue", "略掠"},
        {"lun", "论轮伦仑"},
        {"luo", "落罗骆洛螺锣裸萝"},
        {"ma", "妈麻马骂码蚂"},
        {"mai", "买卖麦脉迈"},
        {"man", "满慢蛮瞒馒"},
        {"mang", "忙盲茫芒"},
        {"mao", "毛帽猫贸茂矛冒"},
        {"me", "么"},
        {"mei", "没美每妹梅煤霉媒"},
        {"men", "们门闷"},
        {"meng", "梦猛蒙盟孟"},
        {"mi", "米密迷秘蜜弥"},
        {"mian", "面棉免眠绵勉"},
        {"miao", "秒苗描渺妙"},
        {"mie", "灭蔑"},
        {"min", "民敏闽"},
        {"ming", "明名命鸣铭"},
        {"miu", "谬"},
        {"mo", "末摸莫磨魔沫默摩漠"},
        {"mou", "某谋牟"},
        {"mu", "母木目幕慕墓牧亩穆"},
        {"na", "那拿哪纳钠"},
        {"nai", "乃奶耐奈"},
        {"nan", "南男难楠"},
        {"nang", "囊"},
        {"nao", "脑闹挠恼瑙"},
        {"ne", "呢"},
        {"nei", "内"},
        {"nen", "嫩"},
        {"neng", "能"},
        {"ni", "你尼拟泥逆腻"},
        {"nian", "年念粘碾"},
        {"niang", "娘酿"},
        {"niao", "鸟尿"},
        {"nie", "捏聂涅"},
        {"nin", "您"},
        {"ning", "宁凝拧"},
        {"niu", "牛纽扭妞"},
        {"nong", "农弄浓"},
        {"nu", "努怒奴"},
        {"nv", "女"},
        {"nuan", "暖"},
        {"nue", "虐"},
        {"nuo", "挪诺糯"},
        {"o", "哦噢"},
        {"ou", "欧偶藕鸥"},
        {"pa", "怕爬趴扒帕"},
        {"pai", "排拍牌派迫"},
        {"pan", "盘判盼叛攀"},
        {"pang", "旁胖庞"},
        {"pao", "跑炮泡抛刨"},
        {"pei", "配陪赔佩培沛"},
        {"pen", "盆喷"},
        {"peng", "碰朋捧棚蓬鹏"},
        {"pi", "批皮片匹劈疲辟僻"},
        {"pian", "片骗偏篇"},
        {"piao", "票飘漂飘"},
        {"pie", "撇"},
        {"pin", "品拼贫频"},
        {"ping", "平评瓶萍凭屏"},
        {"po", "破颇泼婆坡迫"},
        {"pou", "剖"},
        {"pu", "普谱铺朴浦葡"},
        {"qi", "起其期七气汽棋奇齐骑旗乞企契"},
        {"qia", "卡恰"},
        {"qian", "前千钱签浅欠迁谦潜牵"},
        {"qiang", "强枪墙腔抢"},
        {"qiao", "桥瞧悄敲巧翘撬"},
        {"qie", "切且窃"},
        {"qin", "亲秦勤琴禽侵"},
        {"qing", "情清请青轻晴倾庆"},
        {"qiong", "穷琼"},
        {"qiu", "球求秋丘囚"},
        {"qu", "去取曲区趣驱屈渠"},
        {"quan", "全权劝拳圈券泉"},
        {"que", "却确缺雀鹊"},
        {"qun", "群裙"},
        {"ran", "然燃染"},
        {"rang", "让嚷壤"},
        {"rao", "绕饶扰"},
        {"re", "热惹"},
        {"ren", "人任认仁忍韧"},
        {"reng", "仍扔"},
        {"ri", "日"},
        {"rong", "容荣融溶熔绒"},
        {"rou", "肉柔揉"},
        {"ru", "如入儒乳辱"},
        {"ruan", "软"},
        {"rui", "锐瑞蕊"},
        {"run", "润闰"},
        {"ruo", "若弱"},
        {"sa", "撒洒萨"},
        {"sai", "赛塞腮"},
        {"san", "三散伞"},
        {"sang", "桑丧嗓"},
        {"sao", "扫骚嫂"},
        {"se", "色涩"},
        {"sen", "森"},
        {"seng", "僧"},
        {"sha", "杀沙纱傻刹"},
        {"shai", "筛晒"},
        {"shan", "山善闪衫删扇陕"},
        {"shang", "上商伤赏尚"},
        {"shao", "少烧绍邵稍勺"},
        {"she", "社设射涉蛇摄舍"},
        {"shei", "谁"},
        {"shen", "身深神什审申慎甚渗"},
        {"sheng", "生声胜剩省升绳圣"},
        {"shi", "是时事十使十师诗失施石食实识始世势"},
        {"shou", "手收受手售寿瘦守兽"},
        {"shu", "书树属术输数叔束述署鼠"},
        {"shua", "刷耍"},
        {"shuai", "率甩帅衰"},
        {"shuan", "拴栓"},
        {"shuang", "双爽"},
        {"shui", "水谁睡税"},
        {"shun", "顺瞬"},
        {"shuo", "说硕烁"},
        {"si", "四思死司私丝似寺嗣撕"},
        {"song", "送松宋颂诵"},
        {"sou", "搜艘嗖"},
        {"su", "速诉素苏宿俗肃塑"},
        {"suan", "算酸蒜"},
        {"sui", "岁虽随碎髓"},
        {"sun", "孙损笋"},
        {"suo", "所锁缩索"},
        {"ta", "他她它踏塔塌"},
        {"tai", "太台态泰抬"},
        {"tan", "谈弹贪叹坦摊坛探"},
        {"tang", "堂唐糖躺烫塘"},
        {"tao", "套逃桃陶讨涛掏"},
        {"te", "特"},
        {"teng", "疼腾藤"},
        {"ti", "提题体替梯蹄"},
        {"tian", "天田填甜添"},
        {"tiao", "条跳调挑"},
        {"tie", "贴铁帖"},
        {"ting", "听停厅挺亭"},
        {"tong", "通同童痛桶统铜"},
        {"tou", "头投透偷"},
        {"tu", "图土突途涂吐秃"},
        {"tuan", "团"},
        {"tui", "退推腿蜕"},
        {"tun", "吞屯臀"},
        {"tuo", "脱拖托拓妥"},
        {"wa", "挖瓦娃袜蛙"},
        {"wai", "外歪"},
        {"wan", "万完晚弯碗顽腕玩"},
        {"wang", "王望往网忘旺汪"},
        {"wei", "为位委维卫围危喂胃伪"},
        {"wen", "问文温稳吻蚊纹"},
        {"weng", "翁嗡"},
        {"wo", "我握卧沃窝"},
        {"wu", "五无物务武误午吴舞屋污"},
        {"xi", "系西喜希息吸席习细析"},
        {"xia", "下夏吓虾峡瞎侠"},
        {"xian", "现先线限险献县鲜闲宪"},
        {"xiang", "想像项相向香乡详箱享象"},
        {"xiao", "小笑校晓销效肖孝"},
        {"xie", "些写谢协邪鞋胁斜歇"},
        {"xin", "新心信欣辛薪"},
        {"xing", "行兴星型形醒姓幸"},
        {"xiong", "兄熊雄凶胸"},
        {"xiu", "修秀休锈袖绣嗅"},
        {"xu", "许续须需序虚蓄"},
        {"xuan", "选宣旋玄悬"},
        {"xue", "学雪血穴靴"},
        {"xun", "寻训迅讯巡循"},
        {"ya", "呀押牙鸭亚芽崖"},
        {"yan", "言眼烟研严演盐宴颜延"},
        {"yang", "样央羊洋阳养仰痒"},
        {"yao", "要药遥摇咬腰遥邀"},
        {"ye", "也夜叶业野页液"},
        {"yi", "一以已意义易医移依亿"},
        {"yin", "因引音银印阴饮隐"},
        {"ying", "应英影迎营赢硬蝇"},
        {"yo", "哟"},
        {"yong", "用永拥勇涌咏"},
        {"you", "有由又油游友右幽"},
        {"yu", "于与鱼雨语余玉预育域"},
        {"yuan", "元原远院员愿源缘"},
        {"yue", "月约越乐跃岳"},
        {"yun", "运云均韵允"},
        {"za", "杂砸咋"},
        {"zai", "在再灾栽载宰"},
        {"zan", "咱赞暂"},
        {"zang", "脏葬藏"},
        {"zao", "早造糟遭燥"},
        {"ze", "则责泽择"},
        {"zei", "贼"},
        {"zen", "怎"},
        {"zeng", "增憎赠"},
        {"zha", "扎炸闸渣"},
        {"zhai", "宅摘窄债"},
        {"zhan", "站战展占绽斩"},
        {"zhang", "张涨章长障帐"},
        {"zhao", "找照招着赵兆"},
        {"zhe", "这着者折哲"},
        {"zhen", "真镇阵珍振震侦"},
        {"zheng", "正整政证争征睁"},
        {"zhi", "只之制直至知直指值纸支"},
        {"zhong", "中种重终钟众忠"},
        {"zhou", "周州洲粥皱宙"},
        {"zhu", "主住注助猪竹逐祝柱"},
        {"zhua", "抓"},
        {"zhuai", "拽"},
        {"zhuan", "转专赚"},
        {"zhuang", "装壮撞状"},
        {"zhui", "追坠缀"},
        {"zhun", "准"},
        {"zhuo", "桌捉拙酌"},
        {"zi", "子自字资紫姊"},
        {"zong", "总纵宗综棕"},
        {"zou", "走奏揍"},
        {"zu", "组族足祖阻租"},
        {"zuan", "钻"},
        {"zui", "最罪嘴醉"},
        {"zun", "尊遵"},
        {"zuo", "做作左坐昨座"}
    };

    public SimpleKeyboardView(Context context) {
        super(context);
        init(context);
    }

    public SimpleKeyboardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public SimpleKeyboardView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setOrientation(VERTICAL);
        setPadding(8, 8, 8, 8);
        setBackgroundColor(Color.parseColor("#1E1E24"));
        buildKeyboard(context);
    }

    public void setOnTextChangedListener(OnTextChangedListener l) {
        this.listener = l;
    }

    /** 绑定输入显示框 */
    public void bindInputTextView(TextView tv) {
        this.tvInput = tv;
    }

    public String getText() {
        return text.toString();
    }

    public void setText(String s) {
        text.setLength(0);
        if (s != null) {
            text.append(s);
        }
        notifyChanged();
    }

    public void clear() {
        text.setLength(0);
        notifyChanged();
    }

    private void notifyChanged() {
        if (tvInput != null) {
            tvInput.setText(text.toString());
        }
        if (listener != null) {
            listener.onTextChanged(text.toString());
        }
    }

    private void buildKeyboard(Context context) {
        removeAllViews();

        // 输入显示区
        tvInput = new TextView(context);
        tvInput.setTextSize(18);
        tvInput.setTextColor(Color.WHITE);
        tvInput.setPadding(16, 12, 16, 12);
        tvInput.setSingleLine(true);
        tvInput.setBackgroundColor(Color.parseColor("#2A2A30"));
        LayoutParams inputLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        inputLp.bottomMargin = 8;
        addView(tvInput, inputLp);

        if (chineseMode) {
            buildChineseKeyboard(context);
        } else {
            buildEnglishKeyboard(context);
        }
    }

    /** 英文/数字键盘 */
    private void buildEnglishKeyboard(Context context) {
        // 数字行
        String[] row1 = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"};
        addKeyRow(context, row1, false);

        // 字母第一行
        String[] row2 = {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"};
        addKeyRow(context, row2, false);

        // 字母第二行
        String[] row3 = {"a", "s", "d", "f", "g", "h", "j", "k", "l"};
        addKeyRow(context, row3, false);

        // 字母第三行
        String[] row4 = {"z", "x", "c", "v", "b", "n", "m"};
        LinearLayout row4Layout = createKeyRow(context, row4, false);
        // 添加删除键
        Button btnDel = createKey(context, "删除", 1.5f);
        btnDel.setOnClickListener(v -> {
            if (text.length() > 0) {
                text.deleteCharAt(text.length() - 1);
                notifyChanged();
            }
        });
        row4Layout.addView(btnDel);
        addView(row4Layout);

        // 功能行:中/英切换 + 空格 + 确定 + 清空
        LinearLayout funcRow = new LinearLayout(context);
        funcRow.setOrientation(HORIZONTAL);
        funcRow.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        Button btnSwitch = createKey(context, "中文", 1.2f);
        btnSwitch.setOnClickListener(v -> {
            chineseMode = true;
            buildKeyboard(context);
        });
        funcRow.addView(btnSwitch);

        Button btnSpace = createKey(context, "空格", 4f);
        btnSpace.setOnClickListener(v -> {
            text.append(" ");
            notifyChanged();
        });
        funcRow.addView(btnSpace);

        Button btnClear = createKey(context, "清空", 1.2f);
        btnClear.setOnClickListener(v -> clear());
        funcRow.addView(btnClear);

        Button btnEnter = createKey(context, "确定", 1.5f);
        btnEnter.setOnClickListener(v -> {
            if (listener != null) {
                listener.onTextChanged(text.toString());
            }
        });
        funcRow.addView(btnEnter);

        addView(funcRow);
    }

    /** 中文拼音键盘 */
    private void buildChineseKeyboard(Context context) {
        // 声母/韵母行
        String[] row1 = {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"};
        addPinyinRow(context, row1);

        String[] row2 = {"a", "s", "d", "f", "g", "h", "j", "k", "l"};
        addPinyinRow(context, row2);

        String[] row3 = {"z", "x", "c", "v", "b", "n", "m"};
        LinearLayout row3Layout = createPinyinRow(context, row3);
        Button btnDel = createKey(context, "删除", 1.5f);
        btnDel.setOnClickListener(v -> {
            if (text.length() > 0) {
                text.deleteCharAt(text.length() - 1);
                notifyChanged();
            }
        });
        row3Layout.addView(btnDel);
        addView(row3Layout);

        // 功能行
        LinearLayout funcRow = new LinearLayout(context);
        funcRow.setOrientation(HORIZONTAL);
        funcRow.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        Button btnSwitch = createKey(context, "英文", 1.2f);
        btnSwitch.setOnClickListener(v -> {
            chineseMode = false;
            buildKeyboard(context);
        });
        funcRow.addView(btnSwitch);

        Button btnSpace = createKey(context, "空格", 4f);
        btnSpace.setOnClickListener(v -> {
            text.append(" ");
            notifyChanged();
        });
        funcRow.addView(btnSpace);

        Button btnClear = createKey(context, "清空", 1.2f);
        btnClear.setOnClickListener(v -> clear());
        funcRow.addView(btnClear);

        Button btnEnter = createKey(context, "确定", 1.5f);
        btnEnter.setOnClickListener(v -> {
            if (listener != null) {
                listener.onTextChanged(text.toString());
            }
        });
        funcRow.addView(btnEnter);

        addView(funcRow);
    }

    private void addKeyRow(Context context, String[] keys, boolean isPinyin) {
        LinearLayout row = createKeyRow(context, keys, isPinyin);
        addView(row);
    }

    private void addPinyinRow(Context context, String[] keys) {
        LinearLayout row = createPinyinRow(context, keys);
        addView(row);
    }

    private LinearLayout createKeyRow(Context context, String[] keys, boolean isPinyin) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 4;
        row.setLayoutParams(lp);
        for (String k : keys) {
            Button btn = createKey(context, k, 1f);
            btn.setOnClickListener(v -> {
                text.append(k);
                notifyChanged();
            });
            row.addView(btn);
        }
        return row;
    }

    private LinearLayout createPinyinRow(Context context, String[] keys) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 4;
        row.setLayoutParams(lp);
        for (String k : keys) {
            Button btn = createKey(context, k, 1f);
            btn.setOnClickListener(v -> {
                // 显示候选词
                showCandidates(context, k);
            });
            row.addView(btn);
        }
        return row;
    }

    /** 显示拼音候选词 */
    private void showCandidates(final Context context, String pinyin) {
        // 查找匹配的中文
        final List<String> candidates = new ArrayList<>();
        for (String[] entry : pinyinMap) {
            if (entry[0].startsWith(pinyin) && entry.length > 1) {
                // 逐字添加候选
                String chars = entry[1];
                for (int i = 0; i < chars.length(); i++) {
                    String c = String.valueOf(chars.charAt(i));
                    if (!candidates.contains(c)) {
                        candidates.add(c);
                    }
                }
            }
        }

        if (candidates.isEmpty()) {
            // 没有匹配,直接输入字母
            text.append(pinyin);
            notifyChanged();
            return;
        }

        // 显示候选词条
        removeAllViews();
        // 重新显示输入框
        if (tvInput != null) {
            LayoutParams inputLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            inputLp.bottomMargin = 8;
            addView(tvInput, inputLp);
        }

        // 候选词水平滚动条
        LinearLayout candRow = new LinearLayout(context);
        candRow.setOrientation(HORIZONTAL);
        LayoutParams candLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        candLp.bottomMargin = 8;
        candRow.setLayoutParams(candLp);

        for (final String c : candidates) {
            Button btn = createKey(context, c, 0.8f);
            btn.setOnClickListener(v -> {
                text.append(c);
                notifyChanged();
                buildKeyboard(context);
            });
            candRow.addView(btn);
        }
        addView(candRow);

        // 返回键盘按钮
        Button btnBack = createKey(context, "返回键盘", 2f);
        btnBack.setOnClickListener(v -> buildKeyboard(context));
        addView(btnBack);
    }

    private Button createKey(Context context, String label, float weight) {
        Button btn = new Button(context);
        btn.setText(label);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(16);
        btn.setBackgroundResource(R.drawable.bg_btn);
        LayoutParams lp = new LayoutParams(0, LayoutParams.WRAP_CONTENT);
        lp.weight = weight;
        lp.setMargins(3, 3, 3, 3);
        btn.setLayoutParams(lp);
        btn.setMinHeight(0);
        btn.setMinimumHeight(0);
        btn.setPadding(0, 16, 0, 16);
        return btn;
    }
}
