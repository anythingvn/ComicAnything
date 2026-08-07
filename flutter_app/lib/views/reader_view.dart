import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../models/comic_item.dart';

class ReaderView extends StatefulWidget {
  final ComicItem comic;

  const ReaderView({Key? key, required this.comic}) : super(key: key);

  @override
  State<ReaderView> createState() => _ReaderViewState();
}

class _ReaderViewState extends State<ReaderView> {
  bool _showControls = true;
  int _currentPage = 1;
  int _totalPages = 48; // Sample total pages
  ReadingMode _readingMode = ReadingMode.ltr;
  ColorFilterMode _filterMode = ColorFilterMode.amoledBlack;
  bool _autoCropMargins = true;
  double _zoomScale = 1.0;

  @override
  void initState() {
    super.initState();
    _currentPage = widget.comic.currentPage;
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
  }

  @override
  void dispose() {
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    super.dispose();
  }

  void _toggleControls() {
    setState(() {
      _showControls = !_showControls;
    });
  }

  Color _getBackgroundColor() {
    switch (_filterMode) {
      case ColorFilterMode.sepia:
        return const Color(0xFFFBF0D9);
      case ColorFilterMode.night:
        return const Color(0xFF1E1E1E);
      case ColorFilterMode.amoledBlack:
        return Colors.black;
      case ColorFilterMode.highContrast:
        return Colors.black;
      case ColorFilterMode.original:
      default:
        return Colors.white;
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: _getBackgroundColor(),
      body: Stack(
        children: [
          // Canvas Reader Area
          GestureDetector(
            onTapUp: (details) {
              final width = MediaQuery.of(context).size.width;
              final dx = details.localPosition.dx;
              if (dx < width * 0.3) {
                // Previous page
                if (_currentPage > 1) {
                  setState(() => _currentPage--);
                }
              } else if (dx > width * 0.7) {
                // Next page
                if (_currentPage < _totalPages) {
                  setState(() => _currentPage++);
                }
              } else {
                // Center tap: toggle overlay
                _toggleControls();
              }
            },
            child: InteractiveViewer(
              minScale: 1.0,
              maxScale: 4.0,
              onInteractionUpdate: (details) {
                _zoomScale = details.scale;
              },
              child: Center(
                child: Container(
                  margin: EdgeInsets.all(_autoCropMargins ? 0 : 16),
                  decoration: BoxDecoration(
                    color: Colors.white,
                    boxShadow: [
                      BoxShadow(
                        color: Colors.black.withOpacity(0.5),
                        blurRadius: 10,
                      )
                    ],
                  ),
                  child: AspectRatio(
                    aspectRatio: 0.7,
                    child: Stack(
                      alignment: Alignment.center,
                      children: [
                        // Placeholder Comic Page Graphics
                        Icon(
                          Icons.menu_book_rounded,
                          size: 120,
                          color: _filterMode == ColorFilterMode.sepia
                              ? const Color(0xFF8D6E63)
                              : Colors.grey.shade700,
                        ),
                        Positioned(
                          top: 20,
                          child: Text(
                            '${widget.comic.title} - Page $_currentPage / $_totalPages',
                            style: const TextStyle(
                              fontSize: 16,
                              fontWeight: FontWeight.bold,
                              color: Colors.black87,
                            ),
                          ),
                        ),
                        Positioned(
                          bottom: 20,
                          child: Container(
                            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                            decoration: BoxDecoration(
                              color: Colors.black87,
                              borderRadius: BorderRadius.circular(20),
                            ),
                            child: Text(
                              'Mode: ${_readingMode.name.toUpperCase()} | Auto-Crop: ${_autoCropMargins ? "ON" : "OFF"}',
                              style: const TextStyle(color: Colors.white, fontSize: 12),
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),

          // Top Header Overlay Controls
          if (_showControls)
            Positioned(
              top: 0,
              left: 0,
              right: 0,
              child: Container(
                color: Colors.black.withOpacity(0.85),
                padding: EdgeInsets.only(
                  top: MediaQuery.of(context).padding.top + 8,
                  bottom: 12,
                  left: 16,
                  right: 16,
                ),
                child: Row(
                  children: [
                    IconButton(
                      icon: const Icon(Icons.arrow_back, color: Colors.white),
                      onPressed: () => Navigator.pop(context),
                    ),
                    Expanded(
                      child: Text(
                        widget.comic.title,
                        style: const TextStyle(
                          color: Colors.white,
                          fontSize: 16,
                          fontWeight: FontWeight.bold,
                        ),
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                    IconButton(
                      icon: Icon(
                        widget.comic.isFavorite ? Icons.bookmark : Icons.bookmark_border,
                        color: widget.comic.isFavorite ? Colors.amber : Colors.white,
                      ),
                      onPressed: () {
                        setState(() {
                          widget.comic.isFavorite = !widget.comic.isFavorite;
                        });
                      },
                    ),
                    IconButton(
                      icon: const Icon(Icons.tune, color: Colors.white),
                      onPressed: _showSettingsBottomSheet,
                    ),
                  ],
                ),
              ),
            ),

          // Bottom Scrubber Overlay Controls
          if (_showControls)
            Positioned(
              bottom: 0,
              left: 0,
              right: 0,
              child: Container(
                color: Colors.black.withOpacity(0.85),
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text(
                          'Page $_currentPage of $_totalPages',
                          style: const TextStyle(color: Colors.white, fontWeight: FontWeight.bold),
                        ),
                        Text(
                          '${((_currentPage / _totalPages) * 100).toStringAsFixed(0)}%',
                          style: const TextStyle(color: Colors.amberAccent, fontWeight: FontWeight.bold),
                        ),
                      ],
                    ),
                    Slider(
                      value: _currentPage.toDouble(),
                      min: 1,
                      max: _totalPages.toDouble(),
                      divisions: _totalPages,
                      activeColor: Colors.deepOrangeAccent,
                      inactiveColor: Colors.grey.shade800,
                      onChanged: (val) {
                        setState(() {
                          _currentPage = val.toInt();
                        });
                      },
                    ),
                  ],
                ),
              ),
            ),
        ],
      ),
    );
  }

  void _showSettingsBottomSheet() {
    showModalBottomSheet(
      context: context,
      backgroundColor: const Color(0xFF1E1E1E),
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (context) {
        return StatefulBuilder(
          builder: (context, setSheetState) {
            return Padding(
              padding: const EdgeInsets.all(20.0),
              child: SingleChildScrollView(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Text(
                      'Reader Settings',
                      style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold, color: Colors.white),
                    ),
                    const SizedBox(height: 16),
                    const Text('Reading Direction', style: TextStyle(color: Colors.grey)),
                    Wrap(
                      spacing: 8,
                      children: ReadingMode.values.map((mode) {
                        final isSelected = _readingMode == mode;
                        return ChoiceChip(
                          label: Text(mode.name.toUpperCase()),
                          selected: isSelected,
                          selectedColor: Colors.deepOrangeAccent,
                          labelStyle: TextStyle(color: isSelected ? Colors.white : Colors.black),
                          onSelected: (val) {
                            if (val) {
                              setState(() => _readingMode = mode);
                              setSheetState(() {});
                            }
                          },
                        );
                      }).toList(),
                    ),
                    const SizedBox(height: 16),
                    SwitchListTile(
                      title: const Text('Auto-Crop White Margins', style: TextStyle(color: Colors.white)),
                      value: _autoCropMargins,
                      activeColor: Colors.deepOrangeAccent,
                      onChanged: (val) {
                        setState(() => _autoCropMargins = val);
                        setSheetState(() {});
                      },
                    ),
                    const SizedBox(height: 16),
                    const Text('Color Mode & Theme', style: TextStyle(color: Colors.grey)),
                    Wrap(
                      spacing: 8,
                      children: ColorFilterMode.values.map((mode) {
                        final isSelected = _filterMode == mode;
                        return ChoiceChip(
                          label: Text(mode.name.toUpperCase()),
                          selected: isSelected,
                          selectedColor: Colors.amberAccent,
                          labelStyle: const TextStyle(color: Colors.black),
                          onSelected: (val) {
                            if (val) {
                              setState(() => _filterMode = mode);
                              setSheetState(() {});
                            }
                          },
                        );
                      }).toList(),
                    ),
                  ],
                ),
              ),
            );
          },
        );
      },
    );
  }
}
