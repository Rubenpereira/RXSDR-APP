#ifndef FAKE_NCURSES_H
#define FAKE_NCURSES_H

#include <stdio.h>

typedef struct WINDOW WINDOW;

extern WINDOW *stdscr;
extern WINDOW *curscr;
extern WINDOW *newscr;

extern int COLS;
extern int LINES;

#define COLOR_BLACK   0
#define COLOR_RED     1
#define COLOR_GREEN   2
#define COLOR_YELLOW  3
#define COLOR_BLUE    4
#define COLOR_MAGENTA 5
#define COLOR_CYAN    6
#define COLOR_WHITE   7

#define ERR (-1)
#define OK  (0)

#ifndef TRUE
#define TRUE 1
#endif
#ifndef FALSE
#define FALSE 0
#endif

#define A_REVERSE 0x00040000

#define KEY_UP    0403
#define KEY_DOWN  0402

#include <string.h>
#define bzero(s, n) memset(s, 0, (n))
#define bcopy(src, dest, n) memcpy((dest), (src), (n))

#define COLOR_PAIR(n) (n)
int clrtoeol(void);
int timeout(int delay);
int erase(void);

int wscanw(WINDOW *win, const char *fmt, ...);

WINDOW* initscr(void);
int endwin(void);
int cbreak(void);
int nocbreak(void);
int echo(void);
int noecho(void);
int clear(void);
int wclear(WINDOW *win);
int werase(WINDOW *win);
int refresh(void);
int wrefresh(WINDOW *win);
int keypad(WINDOW *win, int bf);
int nodelay(WINDOW *win, int bf);
int curs_set(int visibility);
int start_color(void);
int init_pair(short pair, short f, short b);
int use_default_colors(void);
int has_colors(void);
int getch(void);
int wgetch(WINDOW *win);
int mvprintw(int y, int x, const char *fmt, ...);
int mvwprintw(WINDOW *win, int y, int x, const char *fmt, ...);
int wprintw(WINDOW *win, const char *fmt, ...);
int printw(const char *fmt, ...);
int wmove(WINDOW *win, int y, int x);
int move(int y, int x);
int waddch(WINDOW *win, const char ch);
int addch(const char ch);
int wattron(WINDOW *win, int attrs);
int wattroff(WINDOW *win, int attrs);
int attron(int attrs);
int attroff(int attrs);
int wcolor_set(WINDOW *win, short color_pair_number, void *opts);
WINDOW* newwin(int nlines, int ncols, int begin_y, int begin_x);
int delwin(WINDOW *win);
int box(WINDOW *win, int verch, int horch);
int wbkgdset(WINDOW *win, int ch);
int wbkgd(WINDOW *win, int ch);
int scrollok(WINDOW *win, int bf);
void wtimeout(WINDOW *win, int delay);

#define curses_h
#define ncurses_h

#endif
