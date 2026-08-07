import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'views/home_view.dart';

void main() {
  runApp(const ProviderScope(child: ComicReaderApp()));
}

class ComicReaderApp extends StatelessWidget {
  const ComicReaderApp({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'ComicVerse Reader',
      debugShowCheckedModeBanner: false,
      themeMode: ThemeMode.dark,
      darkTheme: ThemeData(
        useMaterial3: true,
        brightness: Brightness.dark,
        scaffoldBackgroundColor: const Color(0xFF121212),
        colorSchemeSeed: Colors.deepOrangeAccent,
      ),
      home: const HomeView(),
    );
  }
}
