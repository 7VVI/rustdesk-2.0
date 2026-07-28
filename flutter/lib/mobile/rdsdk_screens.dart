import 'package:bot_toast/bot_toast.dart';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:get/get.dart';
import 'package:provider/provider.dart';

import '../common.dart';
import '../common/widgets/overlay.dart';
import '../consts.dart';
import '../desktop/widgets/refresh_wrapper.dart';
import '../main.dart';
import '../models/platform_model.dart';
import 'pages/connection_page.dart';
import 'pages/home_page.dart';
import 'pages/server_page.dart';

/// Standalone single-screen bootstrap for embedding a single RustDesk screen
/// (instead of the whole tabbed client). The public entry-point functions
/// (`rdServerScreen` / `rdConnectScreen`) live in `lib/main.dart` (the root
/// library) so Flutter can resolve them by name; they delegate here.

/// isServer=true -> controlled-end "Share screen" (ServerPage).
/// isServer=false -> control-end "Connection" (ConnectionPage); connecting
/// pushes the remote session within the same Flutter Navigator.
Future<void> rdScreenMain({required bool isServer}) async {
  WidgetsFlutterBinding.ensureInitialized();
  await initEnv(kAppTypeMain);
  if (isServer) {
    androidChannelInit();
    platformFFI.syncAndroidServiceAppDirConfigPath();
  } else {
    androidConnectChannelInit();
  }
  draggablePositions.load();
  await Future.wait([gFFI.abModel.loadCache(), gFFI.groupModel.loadCache()]);
  gFFI.userModel.refreshCurrentUser();
  final PageShape page;
  if (isServer) {
    page = ServerPage();
  } else {
    page = ConnectionPage(appBarActions: const []);
  }
  runApp(_RdApp(page: page));
  await initUniLinks();
}

/// A minimal single-page app shell, mirroring [App] (see main.dart) but hosting
/// exactly one [PageShape] as its home so no bottom tabs are shown.
class _RdApp extends StatelessWidget {
  final PageShape page;
  const _RdApp({required this.page});

  @override
  Widget build(BuildContext context) {
    final botToastBuilder = BotToastInit();
    return RefreshWrapper(builder: (context) {
      return MultiProvider(
        providers: [
          ChangeNotifierProvider.value(value: gFFI.ffiModel),
          ChangeNotifierProvider.value(value: gFFI.imageModel),
          ChangeNotifierProvider.value(value: gFFI.cursorModel),
          ChangeNotifierProvider.value(value: gFFI.canvasModel),
          ChangeNotifierProvider.value(value: gFFI.peerTabModel),
        ],
        child: GetMaterialApp(
          navigatorKey: globalKey,
          debugShowCheckedModeBanner: false,
          title: bind.mainGetAppNameSync(),
          theme: MyTheme.lightTheme,
          darkTheme: MyTheme.darkTheme,
          themeMode: MyTheme.currentThemeMode(),
          home: Scaffold(
            appBar: AppBar(
              centerTitle: true,
              title: Text(page.title),
              actions: page.appBarActions,
            ),
            body: page,
          ),
          localizationsDelegates: const [
            GlobalMaterialLocalizations.delegate,
            GlobalWidgetsLocalizations.delegate,
            GlobalCupertinoLocalizations.delegate,
          ],
          supportedLocales: supportedLocales,
          navigatorObservers: [
            BotToastNavigatorObserver(),
          ],
          builder: (context, child) => AccessibilityListener(
            child: MediaQuery(
              data: MediaQuery.of(context).copyWith(
                textScaler: TextScaler.linear(1.0),
              ),
              child: botToastBuilder(context, child ?? Container()),
            ),
          ),
        ),
      );
    });
  }
}
